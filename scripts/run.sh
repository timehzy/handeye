#!/usr/bin/env bash
# handeye e2e 总控入口（v1 · Android 全链路）
#
# 流程：拉 bootstrap plan（scenario → page/source）→ 按 (page,source) 稳定分组 →
#       逐组 bootstrap（含 APK 新鲜度，build/install 委托 build_install_android.sh）→
#       setup_android.sh 建 adb forward + /health 探测 → 分组跑 orchestrator → 汇总表收尾。
#
# 用法:
#   ./run.sh --scenario <name> [--platform android] [--serial <s>] [--host-port <p>] [--force-reinstall] [-i] [--tail-events]
#   ./run.sh --tag <tag> / --all  [同上]
#   ./run.sh --check-integration [--platform android]
#   ./run.sh snapshot diff <a.json> <b.json>     # 纯 host 侧，不需要设备
#
# 参数:
#   --scenario <name>    场景名（与 --tag / --all 三选一）
#   --tag <tag>          场景标签（与 --scenario / --all 三选一）
#   --all                跑注册表全部场景，顺序同注册顺序
#   --platform <p>       平台（默认 android）。v1 仅 android；ios 落地后放开
#   --serial <s>         目标设备序列号（多机时指定；缺省 $ANDROID_SERIAL 或 adb devices 第一台）
#   --host-port <port>   host 侧端口。已给则跳过建隧道，直接对该端口 probe /health（隧道归你管）
#   --force-reinstall    跳过 APK 新鲜度判定，强制 build + install
#   -i, --interactive    交互模式：每组开始前停下等回车，'q' 主动退出（exit 0）
#                        （注意：是 run.sh 组级断点，不透传给 orchestrator 的场景级 --interactive；
#                        有控制终端时从 /dev/tty 读键——gradle run task 的 standardInput
#                        会抽干管道 stdin，直接 read 会拿到 EOF）
#   --tail-events        实时打印 events：后台子 shell 每 200ms curl "$BASE/events?afterSeq=n"，
#                        每条新 event 打一行 `[tail seq=… t=…] kind=… raw=…`，组结束自动停
#   -h, --help           显示本帮助
#
# 子命令:
#   --check-integration  只做集成前置自检（工具 / JDK / 配置 / 设备 / App 安装与进程），不跑场景
#   snapshot diff <a.json> <b.json>
#                        对比两份 snapshot——纯 host 侧工具，不需要设备 / adb forward，
#                        直接把参数透传给 orchestrator（:demo-android:e2e:run）。
#
# 分组 bootstrap（结构对齐上游 e2e.sh [2/3] 段）:
#   每组 bootstrap 状态三个全局量：LAST_BOOTSTRAP_KEY（本 run 内最近一次成功 bootstrap 的
#   page<TAB>source，同 key 直接跳过）、FRESHNESS_JUDGED / INSTALL_FLAGS（新鲜度只判定一次、
#   结论复用——实际判定在 build_install_android.sh 内部完成，首组传空 flags 让其内判，
#   成功后置 "--skip-build --skip-install"，后续组只重启不重装）。
#   page=demo（demo 单页）本期 fast-path = build_install_android.sh + am start + 端口反查
#   （setup_android.sh 承担）。N2 Task 9 落地 bootstrap_android.sh 后切过去——见
#   ensure_bootstrap_demo() 内注释（上游同名段叫 ensure_bootstrap_main）。
#   source 非空 → fetch_media.sh 素材兜底（Task 14）未落地，warn 并判本组失败、指路 fixtures README，
#   不影响其它组。注意：demo 场景当前声明了空约束素材需求（FixtureNeed.media()），catalog
#   配好后 plan 会反查出非空 source——素材兜底（或场景素材声明裁剪）落地前，含素材组只会跳过。
#
# 前置:
#   - Android device 已连接（adb devices 可见），debug 变体 App 可启动
#   - JDK 17（缺时 macOS: /usr/libexec/java_home -v 17 查看已装版本；或 brew install openjdk@17）
#
# 退出码:
#   0  全部场景通过；或 -i 模式下用户键入 q 主动退出；--check-integration 全项通过
#   1  任一场景失败 / 任一组 bootstrap 失败（其余组照跑，末尾汇总）
#   2  参数错误（含 scenario/tag 未注册——plan 阶段即报出）
#   3  setup 阶段失败（adb forward / 端口反查 / /health 未就绪；与 1 并存时优先 1）
#   4  前置缺失（工具 / 配置 / 设备 / gradlew）
set -eu

. "$(dirname "$0")/_common.sh"

SCRIPT_DIR="$HANDEYE_ROOT/scripts"

# ---- 子命令：snapshot diff（纯 host 侧透传，先于此处拦截以免被 flag 解析拒绝） ----
# :orchestrator 模块无 application 插件（无 :orchestrator:run task），snapshot 子命令由
# :demo-android:e2e 的 E2eMain → ScenarioRunner.cli 在 args[0]=="snapshot" 时分派给
# SnapshotDiffCli。$@ 原样透传（"snapshot diff a b"）。
if [ $# -gt 0 ] && [ "$1" = "snapshot" ]; then
  shift
  [ $# -gt 0 ] && [ "$1" = "diff" ] || { printf '错误: 只支持 snapshot diff <a.json> <b.json>\n' >&2; exit 2; }
  shift
  [ $# -eq 2 ] || { printf '错误: snapshot diff 需要恰好两个参数 <a.json> <b.json>（收到 %d 个）\n' $# >&2; exit 2; }
  # 文件存在性在 host 侧先验：SnapshotDiffCli 虽返 2，但经 gradle JavaExec 包装后
  # 退出码统一成 1（BUILD FAILED），host 预检才能把「参数错」按 exit 2 报告
  for f in "$1" "$2"; do
    [ -f "$f" ] || { printf '错误: 文件不存在: %s\n' "$f" >&2; exit 2; }
  done
  cd "$HANDEYE_ROOT"
  exec ./gradlew :demo-android:e2e:run --args="snapshot diff $1 $2" \
    -Dorg.gradle.configuration-cache=false --console=plain -q
fi

# ---- 参数解析 ----

SCENARIO=""
TAG=""
ALL=0
PLATFORM="android"
SERIAL=""
USER_HOST_PORT=""
INTERACTIVE=0
TAIL_EVENTS=0
CHECK_INTEGRATION=0
FORCE_REINSTALL=0

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --scenario)  SCENARIO="${2:-}"; shift 2 ;;
      --tag)       TAG="${2:-}"; shift 2 ;;
      --all)       ALL=1; shift 1 ;;
      --platform)  PLATFORM="${2:-}"; shift 2 ;;
      --serial)    SERIAL="${2:-}"; shift 2 ;;
      --host-port) USER_HOST_PORT="${2:-}"; shift 2 ;;
      --check-integration) CHECK_INTEGRATION=1; shift 1 ;;
      --force-reinstall) FORCE_REINSTALL=1; shift 1 ;;
      -i|--interactive) INTERACTIVE=1; shift 1 ;;
      --tail-events) TAIL_EVENTS=1; shift 1 ;;
      -h|--help)   usage_from_header "$0" && exit 0 ;;
      *) printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
    esac
  done

  MODE_COUNT=0
  [ -n "$SCENARIO" ] && MODE_COUNT=$((MODE_COUNT + 1))
  [ -n "$TAG" ] && MODE_COUNT=$((MODE_COUNT + 1))
  [ "$ALL" -eq 1 ] && MODE_COUNT=$((MODE_COUNT + 1))
  [ "$CHECK_INTEGRATION" -eq 1 ] && MODE_COUNT=$((MODE_COUNT + 1))
  if [ "$MODE_COUNT" -gt 1 ]; then
    printf '错误: --scenario / --tag / --all / --check-integration 互斥，只能给一个\n' >&2
    usage_from_header "$0" >&2
    exit 2
  fi
  if [ "$MODE_COUNT" -eq 0 ]; then
    printf '错误: --scenario / --tag / --all / --check-integration 至少给一个\n' >&2
    usage_from_header "$0" >&2
    exit 2
  fi
  if [ "$PLATFORM" != "android" ] && [ "$PLATFORM" != "ios" ]; then
    die "--platform 只支持 android 或 ios（收到: $PLATFORM）" 2
  fi
  if [ -n "$USER_HOST_PORT" ]; then
    case "$USER_HOST_PORT" in
      *[!0-9]*) die "--host-port 必须是数字（收到: $USER_HOST_PORT）" 2 ;;
    esac
  fi
}

# ---- preflight ----

# Java 存在性由 require_tools 保证（缺失 exit 4，提示含 java_home 指引）；这里只校验版本，
# 非 17 只警告不阻断——gradle jvmToolchain(17) 可自动 provisioning，拦死反而误伤。
check_jdk() {
  local v
  v=$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
  case "$v" in
    17.*) ;;
    "")   warn "拿不到 java 版本号（java -version 输出异常），按 toolchain 自动 provisioning 处理" ;;
    *)    warn "JDK 版本 $v（建议 17）。若构建报 toolchain 错：macOS 上 /usr/libexec/java_home -v 17 查看已装版本，或 brew install openjdk@17" ;;
  esac
}

preflight() {
  require_tools \
    "adb:Android SDK platform-tools" \
    "curl:macOS 自带" \
    "jq:brew install jq" \
    "java:JDK 17 — macOS 查看已装: /usr/libexec/java_home -v 17；安装: brew install openjdk@17"
  check_jdk
  [ -x "$HANDEYE_ROOT/gradlew" ] || die "gradlew 不可执行：$HANDEYE_ROOT/gradlew（chmod +x gradlew）" 4
  # load_config / resolve_device 缺配置 / 无设备时各自 exit 4（前置缺失）
  load_config
  # 桥接三级配置到 orchestrator catalog 解析：orchestrator 只认 HANDEYE_FIXTURES_JSON
  # 环境变量（BootstrapPlanCli.defaultCatalogJson），FIXTURES_JSON 已被 load_config
  # 归一为绝对路径——plan 拉取与场景跑批的 gradle 调用都靠这次 export 带上 catalog
  export HANDEYE_FIXTURES_JSON="$FIXTURES_JSON"
  resolve_device "$SERIAL"
  # 与 _common.sh 约定一致：$ADB 故意不加引号，分词成 "adb -s <serial>"
  export ANDROID_SERIAL="$SERIAL"
  # --force-reinstall → 环境变量透传给 build_install_android.sh（其内部 freshness 判定消费）
  if [ "$FORCE_REINSTALL" -eq 1 ]; then
    export FORCE_REINSTALL=1
  fi
}

# ---- --check-integration：只做自检，不跑场景 ----

check_integration() {
  [ "$PLATFORM" = "android" ] || die "--check-integration 暂仅支持 android（iOS 链路未落地）" 2
  log "check-integration（android）：逐项自检，全过 exit 0，任一项缺失 exit 4"
  preflight
  local ok=1 apk_path pid
  # App 是否已安装
  apk_path=$($ADB shell pm path "$APP_ID" 2>/dev/null | head -1 | tr -d '\r')
  if [ -z "$apk_path" ]; then
    printf '✗ App 未安装: %s（先跑 %s/build_install_android.sh）\n' "$APP_ID" "$SCRIPT_DIR" >&2
    ok=0
  else
    printf '✓ App 已安装: %s（%s）\n' "$APP_ID" "$apk_path"
  fi
  # App 进程是否存活（debug server 前提是装配器 install 成功，进程在是最基础的信号）
  pid=$($ADB shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | head -1)
  if [ -z "$pid" ]; then
    printf '✗ App 进程未存活（先启动 App 到主页面）\n' >&2
    ok=0
  else
    printf '✓ App 进程存活: pid=%s\n' "$pid"
  fi
  [ "$ok" -eq 1 ] || exit 4
  log "集成前置自检全部通过（隧道 /health 就绪与否由 setup 阶段在建批时确认）"
}

# ---- [1/3] 拉 bootstrap plan ----
# 契约行：name<TAB>page<TAB>source<TAB>host_path<TAB>smb_path（详见 BootstrapPlanCli）。
# 同时承担 scenario/tag 合法性校验（未注册在这里就报错，不等跑批）。

gradle_plan() { # $1 = selector 串（如 "--all" / "--tag smoke" / "select_ratio"）
  ( cd "$HANDEYE_ROOT" && ./gradlew :demo-android:e2e:run \
      --args="--print-bootstrap-plan $1" \
      -Dorg.gradle.configuration-cache=false --console=plain -q )
}

fetch_plan() {
  local selector=""
  if [ "$ALL" -eq 1 ]; then
    selector="--all"
  elif [ -n "$TAG" ]; then
    selector="--tag $TAG"
  else
    selector="$SCENARIO"
  fi
  log "[1/3] 拉 bootstrap plan（orchestrator --print-bootstrap-plan $selector）"
  local out
  if ! out=$(gradle_plan "$selector" 2>&1); then
    printf '%s\n' "$out" >&2
    die "获取 bootstrap plan 失败（scenario/tag 未注册，或 orchestrator 编译失败）" 2
  fi
  PLAN_OUT="$out"
}

# ---- [2/3] 按 (page, source) 稳定分组 ----
# bash 3.2 无关联数组：GROUP_KEYS / GROUP_MEMBERS 用换行分隔的平行串列表，行号即组号。
# 组序 = key 首现顺序（catalog 相对顺序），同 key 场景聚一组只 bootstrap 一次。

TAB=$(printf '\t')

PLAN_OUT=""
GROUP_KEYS=""
GROUP_MEMBERS=""

# 从 plan 文本（$1，每行 name<TAB>page<TAB>source<TAB>host<TAB>smb）构建分组。
# 只消费契约行（防 gradle/JVM 警告混入 stdout 污染解析）。
build_groups() {
  GROUP_KEYS=""
  GROUP_MEMBERS=""
  local line name page source key at
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    name=$(printf '%s' "$line" | awk -F'\t' '{print $1}')
    page=$(printf '%s' "$line" | awk -F'\t' '{print $2}')
    source=$(printf '%s' "$line" | awk -F'\t' '{print $3}')
    key="$page$TAB$source"
    # 已有同 key 组则成员追加（逗号），否则新开组
    at=$(printf '%s\n' "$GROUP_KEYS" | grep -nF -x -- "$key" 2>/dev/null | head -1 | cut -d: -f1)
    if [ -n "$at" ]; then
      GROUP_MEMBERS=$(printf '%s\n' "$GROUP_MEMBERS" \
        | awk -v n="$at" -v m="$name" 'NR==n{$0=$0","m}1')
    else
      GROUP_KEYS="${GROUP_KEYS:+$GROUP_KEYS
}$key"
      GROUP_MEMBERS="${GROUP_MEMBERS:+$GROUP_MEMBERS
}$name"
    fi
  done <<EOF
$1
EOF
}

group_count() { printf '%s\n' "$GROUP_KEYS" | grep -c . || true; }

# 取第 $1 组（1 起）的 key / members
group_key()     { printf '%s\n' "$GROUP_KEYS"    | sed -n "${1}p"; }
group_members() { printf '%s\n' "$GROUP_MEMBERS" | sed -n "${1}p"; }

# ---- 分组 bootstrap ----
# 三个全局态（语义对齐上游 e2e.sh [2/3]）：
#   LAST_BOOTSTRAP_KEY  本 run 内最近一次成功 bootstrap 的 page<TAB>source；同 key 复用
#   FRESHNESS_JUDGED    新鲜度只判定一次；实际判定内聚在 build_install_android.sh
#   INSTALL_FLAGS       首次判定结论："" = 让脚本自判 build+install（首组）；
#                       "--skip-build --skip-install" = 已新鲜/已装，后续组只重启
LAST_BOOTSTRAP_KEY=""
FRESHNESS_JUDGED=0
INSTALL_FLAGS=""

ADB=""
SERIAL=""

# page=demo fast-path bootstrap（上游 ensure_bootstrap_main 对应段）。
# 本期 = build_install_android.sh（含新鲜度）+ am start 拉起 + 端口反查（setup 阶段做）。
# N2 Task 9 落地 bootstrap_android.sh 后，本函数切为单一调用：
#   "$SCRIPT_DIR/bootstrap_android.sh" $INSTALL_FLAGS [--source ...]
# 素材/换页/草稿的细粒度判定都归那个脚本，run.sh 不再内联 am start。
ensure_bootstrap_demo() { # $1 = source（demo 场景实际恒为空）
  local source="$1"

  # source 非空 = 该组声明了素材需求；素材兜底 fetch_media.sh（Task 14）未落地，
  # 早报失败指路，不影响其它组。demo 场景虽用空约束素材声明，catalog 配好后仍会
  # 反查出非空 source 走到这里——素材兜底落地前这类组只能跳过。
  if [ -n "$source" ]; then
    warn "组声明了素材 source=$source，但素材兜底 fetch_media.sh 未落地（Task 14）"
    warn "见 fixtures/README.md 配置素材后重试；本组跳过"
    return 1
  fi

  # 新鲜度只判定一次、结论复用：首组把判定交给 build_install_android.sh 内部
  # （FORCE_REINSTALL 经环境变量透传，--force-reinstall 语义一致）；
  if [ "$FRESHNESS_JUDGED" -eq 0 ]; then
    FRESHNESS_JUDGED=1
    INSTALL_FLAGS=""
  fi

  log "bootstrap（demo fast-path）: build_install_android.sh $INSTALL_FLAGS"
  # $INSTALL_FLAGS 是脚本内构造的受控 token（空 / "--skip-build --skip-install"），需词分割展开
  # shellcheck disable=SC2086
  if ! "$SCRIPT_DIR/build_install_android.sh" $INSTALL_FLAGS; then
    warn "本组 bootstrap 失败（page=demo），跳过该组场景"
    return 1
  fi

  # 拉起 App 到主页面（冷启动进态 deeplink 归 N2 bootstrap_android.sh，v1 直接起主 Activity）
  log "启动 App: $ADB shell am start -n $APP_ID/$ANDROID_MAIN_ACTIVITY"
  $ADB shell am start -n "$APP_ID/$ANDROID_MAIN_ACTIVITY" >/dev/null 2>&1 || true
  if ! wait_app_pid >/dev/null; then
    warn "App 启动后 20s 内进程未出现（$APP_ID），本组跳过"
    return 1
  fi

  # 本 run 已成功 build+install（或确认新鲜）——后续组一律跳过重装
  INSTALL_FLAGS="--skip-build --skip-install"
  return 0
}

# page 分发。上游还有 story 页；本仓 demo 场景全在 demo 页，其它页早报失败不影响其它组。
ensure_bootstrap() { # $1 = page, $2 = source
  local page="$1" source="$2"
  local key="$page$TAB$source"
  if [ -n "$LAST_BOOTSTRAP_KEY" ] && [ "$key" = "$LAST_BOOTSTRAP_KEY" ]; then
    log "同页同素材，跳过 bootstrap"
    return 0
  fi
  local rc=0
  case "$page" in
    demo) ensure_bootstrap_demo "$source" || rc=1 ;;
    *)    warn "page=$page 的 bootstrap 未落地（v1 仅 demo 页），本组跳过"; rc=1 ;;
  esac
  [ "$rc" -eq 0 ] && LAST_BOOTSTRAP_KEY="$key"
  return "$rc"
}

# ---- setup + health ----
# setup_android.sh 只建 adb forward（无后台进程），成功后 forward 刻意保留复用；
# App 重启可能换 debug server 端口，/health 探不通时重建一次 forward 再探。

SETUP_DONE=0
BASE_URL=""
HOST_PORT=""
FORWARD_CREATED=0
TAIL_PID=""

# 收尾：停 events tailer + 移除本脚本建的 adb forward（--host-port 用户自建的不动）。
# EXIT trap 兜底正常收尾与异常中断；snapshot 子命令 exec 走不到这里（那时也没建过隧道）。
cleanup() {
  if [ -n "${TAIL_PID:-}" ]; then
    kill "$TAIL_PID" 2>/dev/null || true
    TAIL_PID=""
  fi
  if [ "${FORWARD_CREATED:-0}" = "1" ] && [ -n "${HOST_PORT:-}" ]; then
    printf '\n==> 清理 adb forward tcp:%s\n' "$HOST_PORT"
    adb forward --remove "tcp:$HOST_PORT" 2>/dev/null || true
  fi
}
trap cleanup EXIT

do_setup() {
  local out hp
  log "setup_android.sh（反查 device 端口 + adb forward + /health）"
  # HOST_PORT 非空（上一组解析值）作为 localPort 传入，跨组复用同一 host 端口
  if ! out=$("$SCRIPT_DIR/setup_android.sh" ${HOST_PORT:+$HOST_PORT}); then
    warn "setup_android.sh 失败"
    return 1
  fi
  printf '%s\n' "$out"
  hp=$(printf '%s\n' "$out" | grep -E '^HANDEYE_HOST_PORT=' | tail -1 | sed -E 's/^HANDEYE_HOST_PORT=([0-9]+).*/\1/')
  if [ -z "$hp" ]; then
    warn "无法从 setup_android.sh 输出解析出 HANDEYE_HOST_PORT"
    return 1
  fi
  HOST_PORT="$hp"
  FORWARD_CREATED=1
  BASE_URL="http://127.0.0.1:$HOST_PORT"
  SETUP_DONE=1
}

# 每组跑场景前保证 forward + /health 就绪。
ensure_setup_and_health() {
  # --host-port：隧道归用户管，跳过建隧道直接 probe
  if [ -n "$USER_HOST_PORT" ]; then
    HOST_PORT="$USER_HOST_PORT"
    BASE_URL="http://127.0.0.1:$USER_HOST_PORT"
    if probe_health "$BASE_URL"; then
      return 0
    fi
    warn "--host-port $USER_HOST_PORT /health 未就绪（隧道由 --host-port 自行管理，本脚本不重建）"
    return 1
  fi

  local just_setup=0
  if [ "$SETUP_DONE" -eq 0 ]; then
    do_setup || return 1
    just_setup=1
  fi
  if probe_health "$BASE_URL"; then
    return 0
  fi
  if [ "$just_setup" -eq 0 ]; then
    warn "/health 未就绪，重建 adb forward 后再探（App 可能换端口重启）"
    do_setup || return 1
    if probe_health "$BASE_URL"; then
      return 0
    fi
  fi
  warn "/health 探测未就绪 —— 检查 App 侧装配器是否 install"
  return 1
}

# ---- --tail-events：后台子 shell 轮询 events 端点 ----
# 端点契约：GET $BASE/events?afterSeq=<n>&limit=1000 → {"events":[{"seq","t","kind",...}]}
tail_events_start() { # $1 = baseUrl
  [ "$TAIL_EVENTS" -eq 1 ] || return 0
  (
    n=0
    while true; do
      body=$(curl -sf -m 2 "$1/events?afterSeq=$n&limit=1000" 2>/dev/null) || { sleep 0.2; continue; }
      lines=$(printf '%s' "$body" | jq -c '.events[]?' 2>/dev/null) || { sleep 0.2; continue; }
      while IFS= read -r ev; do
        [ -z "$ev" ] && continue
        s=$(printf '%s' "$ev" | jq -r '.seq // empty' 2>/dev/null)
        [ -z "$s" ] && continue
        t=$(printf '%s' "$ev" | jq -r '.t // 0' 2>/dev/null)
        k=$(printf '%s' "$ev" | jq -r '.kind // "?"' 2>/dev/null)
        printf '[tail seq=%s t=%s] kind=%s raw=%s\n' "$s" "$t" "$k" "$ev"
        n=$s
      done <<EOF
$lines
EOF
      sleep 0.2
    done
  ) &
  TAIL_PID=$!
  log "--tail-events 已开启（后台轮询 $1/events，每 200ms 一次）"
}

tail_events_stop() {
  if [ -n "$TAIL_PID" ]; then
    kill "$TAIL_PID" 2>/dev/null || true
    wait "$TAIL_PID" 2>/dev/null || true
    TAIL_PID=""
  fi
}

# ---- -i 组级断点：每组开始前停下等回车，q 退出（exit 0） ----

group_pause() { # $1 = 组号, $2 = 组数, $3 = 组描述
  [ "$INTERACTIVE" -eq 1 ] || return 0
  local line
  while true; do
    printf '[BREAK] 组 %s/%s（%s）— 回车继续，q 退出\n' "$1" "$2" "$3"
    # :demo-android:e2e:run 配了 standardInput=System.in，gradle 调用会把脚本 stdin
    # （管道场景下）抽干——有控制终端时直接从 /dev/tty 读键，绕开这条干扰；
    # 无控制终端（纯管道/CI）退回 stdin，EOF 视为继续
    if [ -r /dev/tty ]; then
      read -r line < /dev/tty || return 0
    else
      read -r line || return 0
    fi
    case "$line" in
      ""|c|continue) return 0 ;;
      q|quit|exit)   log "用户主动退出"; exit 0 ;;
      *)             printf '未识别: %s（回车继续 / q 退出）\n' "$line" ;;
    esac
  done
}

# ---- [3/3] 跑一组场景 ----
# 输出落盘 $ART_DIR/run.log 同时 tee stdout。坑 5 对策：管道吃 exit code，用
# PIPESTATUS 拿 gradle 真实退出码（bash 3.2 支持）；[PASS]/[FAIL] 行另用于汇总表。

ART_DIR=""
LOG_FILE=""

run_group_scenarios() { # $1 = 逗号分隔 members
  local members="$1"
  log "跑批: gradlew :demo-android:e2e:run --args=\"$members $BASE_URL\""
  local pipe rc
  # 函数在 if 条件上下文被调，set -e 全程挂起，管道非零退出不会误杀脚本
  ( cd "$HANDEYE_ROOT" && ./gradlew :demo-android:e2e:run --args="$members $BASE_URL" \
      -Dorg.gradle.configuration-cache=false --console=plain ) 2>&1 | tee -a "$LOG_FILE"
  pipe=("${PIPESTATUS[@]}")
  rc=${pipe[0]}
  if [ "$rc" -ne 0 ]; then
    warn "本组有场景未通过（orchestrator exit $rc）: $members"
    return 1
  fi
  return 0
}

# ---- 汇总表 ----
# 逐场景结果从 run.log 的契约行回填："[PASS] <name> (<ms>ms)" / "[FAIL] <name> ..."（含
# orchestrator 合成的 "[FAIL] 场景 <name> 失败: ..." 异常行）。quit 早退未跑的场景标「未跑」。

SUMMARY=""

record_group_result() { # $1 = 组号 $2 = page $3 = source $4 = members $5 = log（可空）$6 = 兜底结果（bootstrap/setup 失败时非空）
  local g="$1" page="$2" source="$3" members="$4" logf="$5" fallback="$6"
  local name res
  for name in $(printf '%s' "$members" | tr ',' ' '); do
    res="$fallback"
    if [ -z "$res" ] && [ -n "$logf" ]; then
      if grep -q "^\[PASS\] $name (" "$logf" 2>/dev/null; then
        res="PASS"
      elif grep -qE "^\[FAIL\] (场景 )?$name[ (]" "$logf" 2>/dev/null; then
        res="FAIL"
      else
        res="未跑"
      fi
    fi
    SUMMARY="${SUMMARY:+$SUMMARY
}组$g|$page|${source:-<默认>}|$name|$res"
  done
}

print_summary() {
  echo ""
  log "汇总（$ART_DIR/run.log）"
  printf '%-6s %-12s %-22s %-28s %s\n' 组 page source 场景 结果
  printf '%s\n' "$SUMMARY" | awk -F'|' '{printf "%-6s %-12s %-22s %-28s %s\n", $1, $2, $3, $4, $5}'
}

# ---- 主流程 ----

main() {
  parse_args "$@"

  if [ "$CHECK_INTEGRATION" -eq 1 ]; then
    check_integration
    exit 0
  fi

  [ "$PLATFORM" = "android" ] || die "--platform $PLATFORM 尚未落地（v1 仅支持 android）" 2

  preflight

  # [0/3] 唤醒设备：熄屏/锁屏下冷启动会卡 Splash，幂等无副作用
  log "[0/3] 唤醒设备"
  $ADB shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
  $ADB shell wm dismiss-keyguard 2>/dev/null || true

  # [1/3] plan
  fetch_plan
  local plan_lines
  plan_lines=$(printf '%s\n' "$PLAN_OUT" | grep -E "^[A-Za-z0-9_]+${TAB}(demo|story)${TAB}" || true)
  if [ -z "$plan_lines" ]; then
    die "bootstrap plan 为空（selector 未命中任何场景）" 2
  fi

  # [2/3] 分组
  log "[2/3] 按 (page, source) 稳定分组"
  build_groups "$plan_lines"
  local n g page source members
  n=$(group_count)
  printf '    本批次 %s 行契约，分 %s 组（page<TAB>source 稳定分组）\n' \
    "$(printf '%s\n' "$plan_lines" | grep -c .)" "$n"
  g=1
  while [ "$g" -le "$n" ]; do
    printf '      [%s] %s → %s\n' "$g" "$(group_key "$g")" "$(group_members "$g")"
    g=$((g + 1))
  done

  # 颜色透传：gradle 起的子 JVM 拿不到 TTY，靠 shell 判 -t 1 传 FORCE_COLOR/NO_COLOR，
  # orchestrator 检测到非 TTY 自动关色
  if [ -n "${NO_COLOR:-}" ]; then
    export NO_COLOR
  elif [ -t 1 ]; then
    export FORCE_COLOR=1
  fi

  # artifacts 目录：<ts>/run.log 全组追加
  ART_DIR="$HANDEYE_ROOT/e2e/artifacts/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$ART_DIR"
  LOG_FILE="$ART_DIR/run.log"
  log "运行日志: $LOG_FILE"

  # [3/3] 逐组 bootstrap → setup/health → 跑场景
  FAILED=0
  SETUP_FAILED=0
  g=1
  while [ "$g" -le "$n" ]; do
    page=$(group_key "$g" | awk -F'\t' '{print $1}')
    source=$(group_key "$g" | awk -F'\t' '{print $2}')
    members=$(group_members "$g")
    echo ""
    log "[3/3] 分组 $g/$n · page=$page source=${source:-<默认>}（场景: $members）"

    group_pause "$g" "$n" "page=$page source=${source:-<默认>}"

    if ! ensure_bootstrap "$page" "$source"; then
      FAILED=1
      record_group_result "$g" "$page" "$source" "$members" "" "bootstrap 失败"
      g=$((g + 1))
      continue
    fi
    if ! ensure_setup_and_health; then
      SETUP_FAILED=1
      record_group_result "$g" "$page" "$source" "$members" "" "setup 失败"
      g=$((g + 1))
      continue
    fi

    printf '\n===== 组 %s/%s · %s · %s · base=%s =====\n' \
      "$g" "$n" "$page" "${source:-<默认>}" "$BASE_URL" | tee -a "$LOG_FILE"
    tail_events_start "$BASE_URL"
    local group_ok=0
    run_group_scenarios "$members" || group_ok=1
    tail_events_stop
    if [ "$group_ok" -ne 0 ]; then
      FAILED=1
    fi
    record_group_result "$g" "$page" "$source" "$members" "$LOG_FILE" ""

    g=$((g + 1))
  done

  print_summary

  # 退出码：场景/bootstrap 失败优先 1；仅 setup 失败 3
  if [ "$FAILED" -ne 0 ]; then
    log "存在失败分组/场景（详见上方各组输出与汇总表）"
    exit 1
  fi
  if [ "$SETUP_FAILED" -ne 0 ]; then
    log "存在 setup 失败分组（隧道 / /health）"
    exit 3
  fi
  log "全部通过"
}

# ---- 自检（隐藏入口，供无设备环境验证纯 host 逻辑） ----
# HANDEYE_SELFTEST=1 ./run.sh —— fixture 驱动：分组解析 / 汇总结果回填 / events tailer。
selftest() {
  local fixture plan logf out_file fake_dir old_path old_tail i
  # 契约行：name<TAB>page<TAB>source<TAB>host<TAB>smb（host/smb 段可空，空段不能错位）
  fixture=$(printf 'alpha\tdemo\t\te2e_media/a.mp4\t\nbeta\tdemo\t\te2e_media/a.mp4\t\ngamma\tstory\te2e_media/s.mp4\tx/y.mp4\tz/w.mp4\ndelta\tdemo\t\te2e_media/a.mp4\t\nepsilon\tstory\te2e_media/s.mp4\tx/y.mp4\tz/w.mp4')

  build_groups "$fixture"
  [ "$(group_count)" -eq 2 ] || { echo "SELFTEST FAIL: 组数 $(group_count) != 2" >&2; return 1; }
  case "$(group_key 1)" in
    "demo${TAB}") : ;;
    *) echo "SELFTEST FAIL: 组1 key '$(group_key 1)'" >&2; return 1 ;;
  esac
  [ "$(group_members 1)" = "alpha,beta,delta" ] || { echo "SELFTEST FAIL: 组1 members '$(group_members 1)'" >&2; return 1; }
  case "$(group_key 2)" in
    "story${TAB}e2e_media/s.mp4") : ;;
    *) echo "SELFTEST FAIL: 组2 key '$(group_key 2)'" >&2; return 1 ;;
  esac
  [ "$(group_members 2)" = "gamma,epsilon" ] || { echo "SELFTEST FAIL: 组2 members '$(group_members 2)'" >&2; return 1; }

  # source 列含空 host/smb 段时分组不错位；key 首现顺序稳定
  plan=$(printf 'one\tdemo\t\th1\ts1\ntwo\tdemo\t\th2\ts2\nthree\tstory\ts\th\ts')
  build_groups "$plan"
  [ "$(group_count)" -eq 2 ] || { echo "SELFTEST FAIL: 空 source 聚合失败（组数 $(group_count)）" >&2; return 1; }
  [ "$(group_members 1)" = "one,two" ] || { echo "SELFTEST FAIL: 组1 members '$(group_members 1)'" >&2; return 1; }
  [ "$(group_members 2)" = "three" ] || { echo "SELFTEST FAIL: 组2 members '$(group_members 2)'" >&2; return 1; }

  # 汇总回填：PASS 契约行 / FAIL 契约行 / orchestrator 合成异常行 / quit 未跑
  logf=$(mktemp)
  printf '[PASS] alpha (12ms)\n[FAIL] beta (30ms)\n[FAIL] \345\234\272\346\231\257 gamma \345\244\261\350\264\245: IOException: boom\n==> Summary: 1/3 passed\n' > "$logf"
  SUMMARY=""
  record_group_result 1 demo "" "alpha,beta,gamma,delta" "$logf" ""
  printf '%s\n' "$SUMMARY" | grep -qF '组1|demo|<默认>|alpha|PASS' || { echo "SELFTEST FAIL: 汇总 alpha" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF '组1|demo|<默认>|beta|FAIL' || { echo "SELFTEST FAIL: 汇总 beta" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF '组1|demo|<默认>|gamma|FAIL' || { echo "SELFTEST FAIL: 汇总 gamma（合成异常行未识别）" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF '组1|demo|<默认>|delta|未跑' || { echo "SELFTEST FAIL: 汇总 delta（quit 未跑应标未跑）" >&2; rm -f "$logf"; return 1; }
  rm -f "$logf"

  # events tailer：fake curl 固定返回两条 events，验证轮询打行格式与 seq 推进
  fake_dir=$(mktemp -d)
  printf '#!/bin/sh\nprintf %%s \047{"events":[{"seq":1,"t":100,"kind":"k1"},{"seq":2,"t":200,"kind":"k2"}]}\047\n' > "$fake_dir/curl"
  chmod +x "$fake_dir/curl"
  out_file=$(mktemp)
  old_path=$PATH
  old_tail=$TAIL_EVENTS
  PATH="$fake_dir:$PATH"
  TAIL_EVENTS=1
  exec 3>&1
  exec >"$out_file"
  tail_events_start "http://127.0.0.1:1"
  # 轮询等 seq=2 行出现（fake curl 冷启动 + 机器负载下 1s 固定 sleep 不稳，最多等 10s）
  i=0
  while [ "$i" -lt 50 ]; do
    grep -q '^\[tail seq=2 t=200\] kind=k2 raw=' "$out_file" 2>/dev/null && break
    sleep 0.2
    i=$((i + 1))
  done
  tail_events_stop
  exec 1>&3
  exec 3>&-
  PATH=$old_path
  TAIL_EVENTS=$old_tail
  rm -rf "$fake_dir"
  grep -q '^\[tail seq=2 t=200\] kind=k2 raw=' "$out_file" \
    || { echo "SELFTEST FAIL: tailer 输出缺失（$(cat "$out_file")）" >&2; rm -f "$out_file"; return 1; }
  rm -f "$out_file"

  echo "SELFTEST OK"
  return 0
}

if [ "${HANDEYE_SELFTEST:-}" = "1" ]; then
  selftest
  exit $?
fi

main "$@"
