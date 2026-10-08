#!/usr/bin/env bash
# handeye · Android 冷启动进态注入一键脚本（build/install + 冷启动 + deeplink 重试 + 双轨就绪判定）
#
# 用法:
#   ./bootstrap_android.sh [--skip-build] [--skip-install] [--no-relaunch] [--skip-forward] [-s serial]
#   ./bootstrap_android.sh --media-path <相对段> [--media-host <路径>] [flags]
#
# 参数:
#   --media-path <path>   沙盒内相对段素材（如 e2e_media/xxx.mp4，脚本拼 files/ 根）。多素材重复该
#                         参数（或单值内以 `|` 分隔）；紧随其后的 --media-host 归到最近一次
#                         --media-path 开启的素材。本期只保证解析与校验，push 落地见下
#   --media-host <path>   素材 Mac 本地绝对路径（push 源），归属最近一次 --media-path 的素材
#   --skip-build          跳过 gradle 构建（委托 build_install_android.sh）
#   --skip-install        跳过 adb install（委托 build_install_android.sh）
#   --no-relaunch         跳过冷启动（App 已在主页面时快速接入）
#   --skip-forward        跳过 setup_android.sh 形式化建隧道（隧道仍由本脚本反查建立，
#                         仅省去二次 setup 探测；run.sh --host-port 场景可用）
#   -s serial             指定目标设备序列号（多机场景）；缺省 $ANDROID_SERIAL 或 adb devices 第一台
#   -h, --help            打帮助
#
# 环境变量:
#   FORCE_REINSTALL=1     跳过 APK 新鲜度判定强制重装（透传给 build_install_android.sh）
#   HANDEYE_BOOTSTRAP_WARMUP_SEC        deeplink 前暖机等待秒数（默认 12，测试可改小）
#   HANDEYE_BOOTSTRAP_SETTLE_SEC        deeplink 后等待秒数（默认 3，测试可改小）
#   HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC wait_ready 双轨判定总超时秒数（默认 30，测试可改小）
#
# 前置:
#   1. adb / curl 在 PATH，bash >= 3.2，目标设备已连接且 USB 调试已授权
#   2. 配置齐全（APP_ID / HANDEYE_DEEPLINK_SCHEME / ANDROID_MAIN_ACTIVITY，见 handeye.example.sh）
#   3. demo 接收端已落地：handeye://bootstrap deeplink 的 args 出现在 /source?name=bootstrap
#
# 输出契约（供 run.sh 消费，解析 stdout 里的 KEY=VALUE 行）:
#   HANDEYE_HOST_PORT=<hostPort>
#   HANDEYE_DEVICE_PORT=<devicePort>
#
# 退出码:
#   0  成功进态（Track A 强判定，或 Track B 弱判定带警告）
#   2  参数错误（未知参数 / 素材相对段非法 / --media-host 出现在 --media-path 之前）
#   3  setup_android.sh 阶段失败（透传其语义：隧道形式化阶段）
#   4  前置缺失（adb / curl 未装，或配置缺失）
#   5  素材 push 失败（fetch_media.sh 未落地或调用失败）
#   6  deeplink 注入失败（3 次重试后 /source?name=bootstrap 仍非 null）
#   7  冷启动失败或秒崩（am start 失败 / 20s 内进程未出现）
#   8  进态超时（30s 内 Track A/B 均未通过）
#
# 实现要点（对上游 bootstrap 结构的取舍）:
#   - 端口隧道单一真相源在本脚本：冷启动后先自行 discover_debug_server_port（_common.sh）
#     建 forward 拿 BASE，deeplink 重试循环要在这个 BASE 上探 /source；成功后若未给
#     --skip-forward 再调 setup_android.sh 做形式化建隧道（host=device 端口时它直接复用
#     本脚本的 forward），其输出作为最终契约。--skip-forward 时以本脚本反查结果 emit 契约。
#   - Track A 判据不是上游的 /state loadState，而是 probe /health + /source?name=bootstrap
#     非 null（Task 8 demo 接收端的契约）。
set -eu

. "$(dirname "$0")/_common.sh"

SCRIPT_DIR="$HANDEYE_ROOT/scripts"

# ---- 常量 ----
readonly STATE_POLL_INTERVAL_SEC=1
WARMUP_SEC="${HANDEYE_BOOTSTRAP_WARMUP_SEC:-12}"
DEEPLINK_SETTLE_SEC="${HANDEYE_BOOTSTRAP_SETTLE_SEC:-3}"
STATE_POLL_TIMEOUT_SEC="${HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC:-30}"

# ---- 全局变量（parse_args 填） ----
SKIP_BUILD=""
SKIP_INSTALL=""
NO_RELAUNCH=""
SKIP_FORWARD=""
SERIAL=""
MEDIA_DEVICE_PATHS=()   # --media-path，沙盒内相对段（如 e2e_media/xxx.mp4）
MEDIA_HOST_PATHS=()     # --media-host，Mac 本地素材绝对路径，与上一数组下标一一对应
MEDIA_ABS_PATHS=()      # 拼根后的设备绝对路径（deeplink work_path 用）
HOST_PORT=""
DEVICE_PORT=""
BASE_URL=""
DEEPLINK=""

# ---- 参数解析 ----

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      # 带值 flag：$# 不足 2 时 shift 2 会在 set -e 下静默 exit 1，这里显式判空报参数错（exit 2）
      --media-path)
        [ -n "${2:-}" ] || die "参数 --media-path 缺少值" 2
        split_pipe "$2"
        local mp
        for mp in "${PIPE_SPLIT_OUT[@]}"; do
          MEDIA_DEVICE_PATHS+=("$mp"); MEDIA_HOST_PATHS+=("")
        done
        shift 2 ;;
      --media-host)
        [ "${#MEDIA_DEVICE_PATHS[@]}" -gt 0 ] || die "--media-host 必须跟在 --media-path 之后" 2
        [ -n "${2:-}" ] || die "参数 --media-host 缺少值" 2
        MEDIA_HOST_PATHS[$((${#MEDIA_DEVICE_PATHS[@]} - 1))]="$2"
        shift 2 ;;
      --skip-build)    SKIP_BUILD=1; shift 1 ;;
      --skip-install)  SKIP_INSTALL=1; shift 1 ;;
      --no-relaunch)   NO_RELAUNCH=1; shift 1 ;;
      --skip-forward)  SKIP_FORWARD=1; shift 1 ;;
      -s)              [ -n "${2:-}" ] || die "参数 -s 缺少设备序列号" 2; SERIAL="$2"; shift 2 ;;
      -h|--help)       usage_from_header "$0" && exit 0 ;;
      *) printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
    esac
  done

  local i device_rel
  for i in "${!MEDIA_DEVICE_PATHS[@]}"; do
    device_rel="${MEDIA_DEVICE_PATHS[$i]}"
    if [ -z "$device_rel" ]; then
      die "--media-path 需要跟一个沙盒内相对段参数（如 e2e_media/xxx.mp4）" 2
    fi
    # 逐素材校验：多素材列已按素材拆开，每个元素都是不含 '|' 的干净相对段。
    # 该值会拼进 deeplink 的 work_path query，挡住逃逸/特殊字符。
    if ! is_valid_relative_path "$device_rel"; then
      printf '错误: --media-path 需为非空相对段，分段非空且不含 ..，仅限字母数字点下划线连字符\n' >&2
      printf "  实际: '%s'\n" "$device_rel" >&2
      usage_from_header "$0" >&2
      exit 2
    fi
  done
}

# 按 `|` 拆出多素材列的元素，保留空段（尾随空段也要留住，否则与 path 下标错位）。
# 结果写全局数组 PIPE_SPLIT_OUT。单值（无 `|`）得到长度 1 的数组。
PIPE_SPLIT_OUT=()
split_pipe() {  # $1 = 待拆字符串
  local s="$1"
  PIPE_SPLIT_OUT=()
  while true; do
    PIPE_SPLIT_OUT+=("${s%%|*}")
    [ "$s" = "${s%%|*}" ] && break
    s="${s#*|}"
  done
}

# 相对段合法性校验：非空、非绝对路径、分段非空且非 '..'、字符集仅限字母数字点下划线连字符。
is_valid_relative_path() {
  local p="$1"
  [ -n "$p" ] || return 1
  case "$p" in /*) return 1 ;; esac   # 非绝对路径（[ 不支持裸 /* 模式，须走 case）
  # 完整 .. 段 / 空段 / 首尾斜杠 → 违规；其余非法字符（非字母数字点下划线连字符斜杠）→ 违规
  case "$p" in
    */../*|../*|*/..|*//*|*/|/*) return 1 ;;
  esac
  case "$p" in
    *[!A-Za-z0-9._/-]*) return 1 ;;
  esac
  return 0
}

# ---- 前置检查 ----

check_prerequisites() {
  # 工具缺失 / 配置缺失统一 exit 4（require_tools / load_config 的 require_vars 语义一致）
  require_tools "adb:Android SDK platform-tools" "curl:macOS 自带"
}

# ---- 素材解析与 push 委派 ----
# 逐素材拼根（files 根 + 相对段）；设备已有则跳过，没有则委派 fetch_media.sh push。
# fetch_media.sh（Task 14）未落地时给清晰指路并按 exit 5（素材 push 失败）终止。
resolve_media() {
  MEDIA_ABS_PATHS=()
  local i
  for i in "${!MEDIA_DEVICE_PATHS[@]}"; do
    local device_rel="${MEDIA_DEVICE_PATHS[$i]}"
    local host_path="${MEDIA_HOST_PATHS[$i]:-}"
    local abs_path="/storage/emulated/0/Android/data/$APP_ID/files/$device_rel"
    MEDIA_ABS_PATHS+=("$abs_path")
    log "media[$i] device_path(相对)=$device_rel"
    log "media[$i] device_path(绝对)=$abs_path"

    if $ADB shell test -f "$abs_path" 2>/dev/null; then
      log "media[$i] 设备沙盒已有素材，跳过 push"
      continue
    fi

    local fetch_media="$SCRIPT_DIR/fetch_media.sh"
    if [ ! -f "$fetch_media" ]; then
      printf '错误: media[%s] 设备沙盒无素材，需要 push，但 fetch_media.sh 未落地（Task 14）\n' "$i" >&2
      printf '  先把素材放到设备路径 %s，或等 Task 14 落地后提供 --media-host 拉取\n' "$abs_path" >&2
      exit 5
    fi
    if [ -z "$host_path" ]; then
      printf '错误: media[%s] 设备沙盒无素材且未给 --media-host（无可拉取源）\n' "$i" >&2
      exit 5
    fi
    log "media[$i] 从 '$host_path' 拉取素材（fetch_media.sh）"
    if ! "$fetch_media" --smb "$host_path" --device-path "$device_rel" 2>&1; then
      printf '错误: media[%s] 素材 push 失败（源: %s）\n' "$i" "$host_path" >&2
      exit 5
    fi
  done
}

# ---- 构建 + 安装 ----
# 单一真相源在 build_install_android.sh（含新鲜度判定）；本脚本只透传开关。
# FORCE_REINSTALL 经环境变量天然透传，无需额外处理。
build_and_install() {
  local args=()
  [ -n "$SKIP_BUILD" ] && args+=("--skip-build")
  [ -n "$SKIP_INSTALL" ] && args+=("--skip-install")
  log "build_install_android.sh ${args[*]:-（freshness 自判）}"
  "$SCRIPT_DIR/build_install_android.sh" ${args[@]+"${args[@]}"}
}

# ---- 冷启动 ----
# 全新安装场景：force-stop 清残留进程，am start 拉主 Activity，等进程出现。
# 20s 内进程未出现 = 冷启动失败 exit 7。
cold_start() {
  log "冷启动: force-stop $APP_ID"
  $ADB shell am force-stop "$APP_ID" 2>/dev/null || true
  log "冷启动: am start -n $APP_ID/$ANDROID_MAIN_ACTIVITY"
  if ! $ADB shell am start -n "$APP_ID/$ANDROID_MAIN_ACTIVITY" >/dev/null 2>&1; then
    die "am start 失败（包名 $APP_ID · 主 Activity $ANDROID_MAIN_ACTIVITY）" 7
  fi
  local pid
  if ! pid=$(wait_app_pid); then
    printf '错误: 冷启动后 20s 内 App 进程未出现（%s）\n' "$APP_ID" >&2
    printf '  查 crash 栈: %s logcat -d -s AndroidRuntime:E | tail -50\n' "$ADB" >&2
    exit 7
  fi
  log "App 进程存活 pid=$pid"
}

# ---- 端口隧道（本脚本自行反查，单一真相源） ----
# discover_debug_server_port 成功后会留下 tcp:<port>→tcp:<port> 的 adb forward 给调用方复用，
# 这里直接拿它当隧道用，BASE_URL 供 deeplink 重试循环与 wait_ready 探测。
ensure_tunnel() {
  local pid="$1"
  log "反查 debug server 端口（discover_debug_server_port）"
  if ! DEVICE_PORT=$(discover_debug_server_port "$pid"); then
    die "debug server 端口反查失败（App 装配器 HandeyeInstaller.install 是否已成功？）" 4
  fi
  HOST_PORT="$DEVICE_PORT"
  BASE_URL="http://127.0.0.1:$HOST_PORT"
  log "debug server BASE=$BASE_URL（host=device 端口，复用反查留下的 forward）"
}

# ---- deeplink 注入（带重试） ----
# 上游坑 1/7：新装包冷启动窗口不足、按需解压的 so 未就绪，此时 deeplink 注入必失败。
# 最多 3 次：force-stop → launcher 暖机 → deeplink；每次 deeplink 后等 /source?name=bootstrap
# 非 null（fast 判定）再继续；第 3 次仍失败 exit 6。
inject_deeplink() {
  log "deeplink 注入: $DEEPLINK（最多 3 次，暖机 ${WARMUP_SEC}s/次）"
  local attempt body
  for attempt in 1 2 3; do
    $ADB shell am force-stop "$APP_ID"
    # launcher 暖机：把 App 拉到前台走完冷启动初始化（monkey 不可用时静默跳过，靠暖机等待兜底）
    $ADB shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep "$WARMUP_SEC"   # 暖机等价窗口（上游热跑捷径的经验值）
    log "第 $attempt/3 次: am start deep link"
    $ADB shell am start -a android.intent.action.VIEW -d "$DEEPLINK" >/dev/null 2>&1 || true
    sleep "$DEEPLINK_SETTLE_SEC"
    body="$(curl -sf -m 2 "$BASE_URL/source?name=bootstrap" 2>/dev/null || true)"
    if [ -n "$body" ] && [ "$body" != "null" ]; then
      log "deeplink 注入成功（第 $attempt 次，/source?name=bootstrap 非 null）"
      return 0
    fi
    warn "第 $attempt/3 次 deeplink 后 bootstrap source 仍为空（冷启动窗口/so 未就绪？）"
    [ "$attempt" = 3 ] || warn "重试：force-stop → 暖机 → deeplink"
  done
  printf '错误: deeplink 注入失败（3 次重试后 /source?name=bootstrap 仍非 null）\n' >&2
  printf '  最近 logcat:\n' >&2
  $ADB logcat -d 2>/dev/null | tail -30 >&2 || true
  exit 6
}

# ---- 双轨进态判定 ----
# Track A（强判定）: /health 就绪 + /source?name=bootstrap 非 null。
# Track B（弱判定）: pidof 非空 + dumpsys activity activities 匹配 $ANDROID_MAIN_ACTIVITY；
#                   通过时打 warn（debug server 未就绪，场景跑批可能拿不到事件流）。
# 超时内任一 track 通过即返 0；都未通过 exit 8 并附 logcat 尾部 30 行。
wait_ready() {
  log "双轨进态判定 · ${STATE_POLL_TIMEOUT_SEC}s 内 App 进主页面即返 0"
  local waited=0
  local last_health="" last_src="" last_pidof="" last_top=""
  while [ "$waited" -lt "$STATE_POLL_TIMEOUT_SEC" ]; do

    # ---- Track A ----
    last_health="$(curl -sf -m 2 "$BASE_URL/health" 2>/dev/null || true)"
    if printf '%s' "$last_health" | grep -Eq '"ok": ?true'; then
      last_src="$(curl -sf -m 2 "$BASE_URL/source?name=bootstrap" 2>/dev/null || true)"
      if [ -n "$last_src" ] && [ "$last_src" != "null" ]; then
        log "[Track A] /health 就绪 + bootstrap source 非 null（等了 ${waited}s）"
        return 0
      fi
    fi

    # ---- Track B ----
    last_pidof="$($ADB shell "pidof $APP_ID" 2>/dev/null | tr -d '\r' || true)"
    if [ -n "$last_pidof" ]; then
      # dumpsys 输出字段名各厂商不同（AOSP topResumedActivity= / ColorOS ResumedActivity: /
      # task stack Hist #N），三种都识别
      last_top="$($ADB shell "dumpsys activity activities" 2>/dev/null \
        | grep -E "topResumedActivity=.*$ANDROID_MAIN_ACTIVITY|ResumedActivity:.*$ANDROID_MAIN_ACTIVITY|Hist.*$ANDROID_MAIN_ACTIVITY" \
        | head -1 || true)"
      if [ -n "$last_top" ]; then
        log "[Track B] pidof=$last_pidof, top-Activity=$ANDROID_MAIN_ACTIVITY（等了 ${waited}s）"
        warn "Track A 未通过：debug server 未就绪；Track B 弱判定通过（bootstrap source 不可探）"
        return 0
      fi
    fi

    sleep "$STATE_POLL_INTERVAL_SEC"
    waited=$((waited + STATE_POLL_INTERVAL_SEC))
  done

  printf '错误: 进态超时（%ss 内 Track A/B 均未通过）\n' "$STATE_POLL_TIMEOUT_SEC" >&2
  printf '  Track A · 最后 /health: %s\n' "${last_health:-（无响应）}" >&2
  printf '  Track A · 最后 /source?name=bootstrap: %s\n' "${last_src:-（无响应）}" >&2
  printf "  Track B · 最后 pidof='%s' · top-Activity='%s'\n" "$last_pidof" "$last_top" >&2
  printf '  最近 logcat:\n' >&2
  $ADB logcat -d 2>/dev/null | tail -30 >&2 || true
  exit 8
}

# ---- setup 形式化阶段 ----
# deeplink 成功后隧道已在（本脚本反查留下的 forward）；未给 --skip-forward 时调
# setup_android.sh 做形式化建隧道 + /health 探测（host=device 端口时它直接复用），
# 其 HANDEYE_HOST_PORT/HANDEYE_DEVICE_PORT 输出作为最终契约。失败 exit 3（setup 阶段）。
formalize_tunnel() {
  [ -n "$SKIP_FORWARD" ] && { log "跳过 setup_android.sh 形式化建隧道（--skip-forward）"; return 0; }
  log "setup_android.sh 形式化建隧道 + /health 探测"
  local out rc=0 hp dp
  out=$("$SCRIPT_DIR/setup_android.sh") || rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -ne 0 ]; then
    die "setup_android.sh 返 $rc（setup 阶段失败）" 3
  fi
  hp=$(printf '%s\n' "$out" | grep -E '^HANDEYE_HOST_PORT=' | tail -1 | cut -d= -f2)
  dp=$(printf '%s\n' "$out" | grep -E '^HANDEYE_DEVICE_PORT=' | tail -1 | cut -d= -f2)
  if [ -z "$hp" ] || [ -z "$dp" ]; then
    die "无法从 setup_android.sh 输出解析 HANDEYE_HOST_PORT/DEVICE_PORT" 3
  fi
  HOST_PORT="$hp"; DEVICE_PORT="$dp"
  BASE_URL="http://127.0.0.1:$HOST_PORT"
}

# ---- 结果输出 ----

emit_result() {
  echo ""
  echo "HANDEYE_HOST_PORT=$HOST_PORT"
  echo "HANDEYE_DEVICE_PORT=$DEVICE_PORT"
  cat <<EOF

✅ 冷启动进态完成
    base:      $BASE_URL
    bootstrap: $(curl -sf -m 2 "$BASE_URL/source?name=bootstrap" 2>/dev/null || echo '?')

紧接可跑: ./scripts/run.sh --scenario <name> --host-port $HOST_PORT
EOF
}

# ---- 主流程 ----

main() {
  parse_args "$@"
  # 配置加载放在 parse_args 之后：-h 不需要任何配置（与 build_install_android.sh 一致）
  load_config
  check_prerequisites
  resolve_device "$SERIAL"

  run_hook pre-bootstrap

  if [ "${#MEDIA_DEVICE_PATHS[@]}" -gt 0 ]; then
    resolve_media
  fi

  # deeplink 组装：scheme 来自配置；media 模式附 work_path（多素材 `|` 连接，
  # deep link 里写 %7C：adb shell 会把 -d 参数二次交给设备端 sh，裸 `|` 会被当管道）
  DEEPLINK="${HANDEYE_DEEPLINK_SCHEME}://bootstrap"
  if [ "${#MEDIA_ABS_PATHS[@]}" -gt 0 ]; then
    local work_path_join
    work_path_join=$(printf '%s|' "${MEDIA_ABS_PATHS[@]}")
    DEEPLINK="$DEEPLINK?work_path=${work_path_join%|}"
    DEEPLINK="${DEEPLINK//|/%7C}"
  fi

  # 构建 + 安装（含新鲜度判定；--skip-build --skip-install 时对方直接短路返回）
  build_and_install

  if [ -z "$NO_RELAUNCH" ]; then
    cold_start
  else
    log "跳过冷启动（--no-relaunch），接入已在跑的 App"
    if ! wait_app_pid >/dev/null; then
      die "--no-relaunch 但 App 进程不在（$APP_ID），去掉 --no-relaunch 或先手动启动 App" 7
    fi
  fi

  local pid
  pid=$($ADB shell "pidof $APP_ID" 2>/dev/null | tr -d '\r' | head -1)
  [ -n "$pid" ] || die "拿不到 App 进程 pid" 7
  ensure_tunnel "$pid"
  inject_deeplink
  formalize_tunnel
  wait_ready
  run_hook post-bootstrap
  emit_result
}

main "$@"
