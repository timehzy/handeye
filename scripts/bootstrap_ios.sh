#!/usr/bin/env bash
# handeye · iOS 冷启动进态注入一键脚本（build/install + 素材 push + 配置注入 + 冷启动重拉 + 隧道 + 就绪判定）
#
# 用法:
#   ./bootstrap_ios.sh [--skip-build] [--skip-install] [--no-relaunch] [--skip-forward] [-u udid]
#   ./bootstrap_ios.sh --media-path <相对段> [--media-host <路径>] [flags]
#
# 参数:
#   --media-path <path>   沙盒内相对段素材（如 e2e_media/xxx.mp4）。多素材重复该参数（或单值
#                         内以 `|` 分隔）；文件落盘到容器 Documents/handeye/media/<basename>，
#                         进 deeplink 的 work_path 为相对 Documents 段 handeye/media/<basename>
#                         （Documents 根 UUID 运行时随机，host 无法静态推断，上游约定）。
#                         紧随其后的 --media-host 归到最近一次 --media-path 开启的素材；
#                         注意 `--media-path 'a|b'` 一次开启多个素材时 --media-host 只归其中
#                         最后一个素材——每个素材各带 host 须重复 --media-path 分别成组传入
#   --media-host <path>   素材 Mac 本地绝对路径（push 源），归属最近一次 --media-path 的素材。
#                         iOS 侧 push 由本脚本走 afcclient 完成（不经过 fetch_media.sh），
#                         设备上已有该素材时跳过 push、无需此参数
#   --skip-build          跳过 xcodebuild（委托 build_install_ios.sh）
#   --skip-install        跳过 devicectl install（委托 build_install_ios.sh）
#   --no-relaunch         跳过冷启动重拉（App 已在目标态且配置已注入时快速接入）
#   --skip-forward        跳过 setup_ios.sh 建 iproxy 隧道与就绪判定（隧道 / 跑批由外部编排
#                         统一建，如 run.sh --host-port 场景）
#   -u udid               指定目标设备 udid；缺省取 $HANDEYE_UDID / fixtures 的
#                         .devices.ios_udid / idevice_id -l 第一台（多机时须显式指定，
#                         idevice_id 输出排序不可控，取第一台可能取错设备）
#   -h, --help            打帮助
#
# 环境变量:
#   HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC  wait_ready 就绪判定总超时秒数（默认 30，测试可改小）
#   HANDEYE_BOOTSTRAP_SETUP_TIMEOUT_SEC  等 setup_ios.sh 契约行秒数（默认 40，测试可改小）
#
# 机制（与 Android bootstrap_android.sh 对齐的 iOS 等价物）:
#   Android 用 `adb shell am start -d "handeye://bootstrap?work_path=..."` 注入参数；
#   iOS 无 am start，改为:
#     0. build_install_ios.sh 把 Debug 包装到真机（构建+安装，可 --skip）
#     1. afcclient --container 把素材 push 进容器 Documents/handeye/media/
#     2. afcclient --container 写容器 Documents/handeye_bootstrap.json（key url = bootstrap deeplink）
#     3. 真杀进程 + relaunch App → 冷启动完成回调读 handeye_bootstrap.json → 进目标态
#     4. setup_ios.sh 建 iproxy 隧道 + probe /health
#
# 前置:
#   1. libimobiledevice 已装：idevice_id / afcclient / iproxy 在 PATH（brew install libimobiledevice）
#   2. Xcode 已装：devicectl 在 PATH（Xcode 自带 xcrun devicectl）
#   3. iOS 真机 USB 连接（idevice_id -l 可见），已解锁并信任本机
#   4. 配置齐全（APP_ID / HANDEYE_DEEPLINK_SCHEME，见 handeye.example.sh）
#   5. demo 接收端已落地：冷启动完成回调读 handeye_bootstrap.json，args 出现在 /source?name=bootstrap
#
# 输出契约（供 run.sh 消费，解析 stdout 里的 KEY=VALUE 行；--skip-forward 时不输出）:
#   HANDEYE_HOST_PORT=<hostPort>
#   HANDEYE_DEVICE_PORT=<devicePort>
#
# 隧道生命周期:
#   iOS 隧道是 iproxy 进程，必须由活进程持有（与 Android adb forward 的持久语义不同）。
#   本脚本把 setup_ios.sh 后台拉起：它输出契约行后前台驻留持有 iproxy，本脚本读契约行后
#   正常退出、刻意把该后台进程留下继续持隧道（等价 Android 侧 bootstrap 后 forward 仍在）。
#   拆除隧道: kill 输出里给出的 setup_ios 进程（它退出时 EXIT trap 会清掉 iproxy）。
#   失败路径（非零退出）本脚本的 EXIT trap 会代杀该后台进程，不残留占用 host 端口。
#
# 退出码:
#   0  成功进态（/source?name=bootstrap 非 null）
#   2  参数错误（未知参数 / 素材相对段非法 / --media-host 出现在 --media-path 之前）
#   3  setup_ios.sh 阶段失败（隧道未建立 / 契约行缺失 / 进程中途退出）
#   4  前置缺失（idevice_id / afcclient / devicectl / iproxy / curl 未装 / 配置缺失 / 无真机 udid）
#   5  素材 push 失败（afcclient push 失败 / 设备无素材且未给 --media-host）
#   6  写配置失败（afcclient 推 handeye_bootstrap.json 失败；iOS 侧 deeplink 注入等价物）
#   7  冷启动重拉失败（devicectl launch 失败 / --no-relaunch 但进程不在）
#   8  进态超时（30s 内 /source?name=bootstrap 仍为 null，未达成进态）
#   9  xcodebuild 失败（build_install_ios.sh 透传）
#   10 devicectl install 失败（build_install_ios.sh 透传）
#
# 实现要点（对上游 bootstrap 结构的取舍）:
#   - 配置注入用文件（Documents/handeye_bootstrap.json）而非 deeplink 命令：iOS 无 am start，
#     relaunch 后由 App 冷启动完成回调自读。因此无 Android 侧的 deeplink 重试循环
#     （上游坑 1 的暖机/so 未就绪窗口在 iOS 由 launch 后的就绪判定覆盖）。
#   - afcclient 一律 --container（不是 --documents）：iOS 26 上 --documents 直接列根会
#     Permission denied；--container 进 App 容器根（/ 下有 Documents/ Library/ 等），
#     写路径拼 Documents/ 前缀。
#   - 冷启动重拉必须真杀进程（SIGKILL）：terminate 语义若只把 App 退后台，热启动唤醒
#     不会重读 handeye_bootstrap.json，换素材跨分组时配置不生效（上游坑 4）。
#   - afcclient 的 put LOCALPATH 按空格 token 切分，host 本地路径含中文/空格会错误分词
#     甚至卡死：push 前先复制到 /tmp 的 ASCII 临时路径再喂 afcclient。
#   - afcclient 的 put 覆盖已存在文件不截断：写 handeye_bootstrap.json 前先 rm 旧文件，
#     否则旧内容残留污染 JSON（App 解析失败 = 不进态）。
#   - 进态判据只有强判定（/source?name=bootstrap 非 null）：iOS 无 dumpsys/top-Activity
#     弱判定等价物（ Track B 占位在上游亦未实现），超时时附排查指引。
set -eu

. "$(dirname "$0")/_common.sh"

SCRIPT_DIR="$HANDEYE_ROOT/scripts"

# ---- 常量 ----
readonly DEVICE_MEDIA_DIR="handeye/media"          # 素材落盘目录（相对 Documents）
readonly BOOTSTRAP_CONFIG_NAME="handeye_bootstrap.json"
readonly STATE_POLL_INTERVAL_SEC=2

# 超时环境变量数字校验（m4）：非数字给中文 warn 并回退默认值，否则 $((...)) 算术
# 会抛 integer expression expected 裸错误让人摸不着头脑。
validate_timeout() { # $1=值 $2=变量名 $3=默认值；stdout = 可用值
  case "$1" in
    ''|*[!0-9]*) warn "$2 非数字（'$1'），回退默认值 $3"; printf '%s' "$3" ;;
    *)          printf '%s' "$1" ;;
  esac
}
STATE_POLL_TIMEOUT_SEC=$(validate_timeout "${HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC:-30}" HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC 30)
SETUP_TIMEOUT_SEC=$(validate_timeout "${HANDEYE_BOOTSTRAP_SETUP_TIMEOUT_SEC:-40}" HANDEYE_BOOTSTRAP_SETUP_TIMEOUT_SEC 40)

# ---- 全局变量（parse_args 填） ----
SKIP_BUILD=""
SKIP_INSTALL=""
NO_RELAUNCH=""
SKIP_FORWARD=""
UDID=""
MEDIA_DEVICE_PATHS=()   # --media-path，沙盒内相对段（如 e2e_media/xxx.mp4）
MEDIA_HOST_PATHS=()     # --media-host，Mac 本地素材绝对路径，与上一数组下标一一对应
DEVICE_RELS=()          # push_media 填：每个素材相对 Documents 的路径（组 work_path）
HOST_PORT=""
DEVICE_PORT=""
BASE_URL=""
SETUP_PID=""            # 后台 setup_ios.sh 进程（持隧道）
SETUP_OUT=""            # 其 stdout 落盘文件（读契约行用）
SKIP_FORWARD_SET=""     # --skip-forward 生效标记（wait_ready / emit_result 分岔）
KEEP_SETUP=""           # 成功路径置 1：EXIT trap 不杀后台 setup_ios，隧道留用
DEVICECTL=""            # devicectl 调用前缀（check_prerequisites 解析：裸 devicectl 或 "xcrun devicectl"）

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
      -u)              [ -n "${2:-}" ] || die "参数 -u 缺少 udid" 2; UDID="$2"; shift 2 ;;
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
    # 该值的 basename 会落盘进容器并拼进 deeplink 的 work_path query，挡住逃逸/特殊字符。
    if ! is_valid_relative_path "$device_rel"; then
      printf '错误: --media-path 需为非空相对段，分段非空且不含 ..，仅限字母数字点下划线连字符\n' >&2
      printf "  实际: '%s'\n" "$device_rel" >&2
      usage_from_header "$0" >&2
      exit 2
    fi
  done
}

# ---- 前置检查 ----

check_prerequisites() {
  # 工具缺失 / 配置缺失统一 exit 4（require_tools / require_vars 语义一致）
  require_tools \
    "idevice_id:brew install libimobiledevice" \
    "afcclient:brew install libimobiledevice" \
    "iproxy:brew install libimobiledevice" \
    "curl:macOS 自带"
  # devicectl 单独判：Xcode 自带 xcrun devicectl，PATH 里可能没有裸 devicectl（对齐 build_install_ios.sh）。
  # 通过后立即解析出 $DEVICECTL 调用前缀，后续所有调用点统一走它——否则前置宽容、运行期
  # 裸 devicectl command-not-found 被 2>/dev/null 吞掉，进程存活/冷启动会被误判（M1）。
  if ! command -v devicectl >/dev/null 2>&1 && ! xcrun devicectl --help >/dev/null 2>&1; then
    printf '缺少 devicectl（冷启动重拉 App；Xcode 自带 xcrun devicectl，缺则装完整 Xcode）\n' >&2
    exit 4
  fi
  if command -v devicectl >/dev/null 2>&1; then
    DEVICECTL="devicectl"
  else
    DEVICECTL="xcrun devicectl"
  fi
}

# ---- udid 解析 ----
# 优先级：-u 参数 > $HANDEYE_UDID > fixtures 的 .devices.ios_udid（jq 读取）>
# idevice_id -l 第一台。仅走到 fixtures 分支才需要 jq（对齐 setup_ios.sh 的惰性依赖惯例：
# 该分支缺 jq 直接 die，因为后续 afcclient/devicectl 都要 udid，拿不到就没法继续）。
resolve_udid() {
  if [ -n "$UDID" ]; then
    log "目标设备（-u 参数）: $UDID"
    return
  fi
  if [ -n "${HANDEYE_UDID:-}" ]; then
    UDID=$HANDEYE_UDID
    log "目标设备（HANDEYE_UDID）: $UDID"
    return
  fi
  command -v jq >/dev/null 2>&1 || die "未找到 jq，无法读取 \$FIXTURES_JSON 的 .devices.ios_udid（brew install jq；或用 -u / HANDEYE_UDID 显式指定设备以跳过）" 4
  UDID=$(jq -r '.devices.ios_udid // ""' "$FIXTURES_JSON" 2>/dev/null || true)
  if [ -n "$UDID" ]; then
    log "目标设备（fixtures .devices.ios_udid）: $UDID"
    return
  fi
  local devices count
  devices=$(idevice_id -l 2>/dev/null || true)
  count=$(printf '%s\n' "$devices" | awk 'NF {n++} END {print n+0}')
  if [ "$count" -eq 0 ]; then
    die "没有连接的 iOS 真机（idevice_id -l 为空）。确认 USB 连接 + 解锁 + 信任本机" 4
  fi
  if [ "$count" -gt 1 ]; then
    warn "检测到 $count 台设备且未指定（-u / HANDEYE_UDID / fixtures），idevice_id 输出排序不可控，使用第一台——多机场景请显式指定以免取错设备"
  fi
  UDID=$(printf '%s\n' "$devices" | head -1)
  log "目标设备（idevice_id -l 第一台）: $UDID"
}

# ---- 构建 + 安装 ----
# 单一真相源在 build_install_ios.sh；本脚本只透传开关与 udid。
# 其退出码原样透传：9 = xcodebuild 失败 / 10 = devicectl install 失败（set -e 天然传播）。
build_and_install() {
  local args=(-u "$UDID")
  [ -n "$SKIP_BUILD" ] && args+=("--skip-build")
  [ -n "$SKIP_INSTALL" ] && args+=("--skip-install")
  log "build_install_ios.sh ${args[*]}"
  "$SCRIPT_DIR/build_install_ios.sh" ${args[@]+"${args[@]}"}
}

# ---- afcclient 交互 ----
# afcclient 交互 shell 的一次性驱动。命令通过 stdin 喂给 afcclient。
# 用 --container（不是 --documents）：iOS 26 上 --documents 直接列根会 Permission denied，
# --container 进 App 容器根（/ 下有 Documents/ Library/ 等），写路径拼 Documents/ 前缀。
# 注意: afcclient 无单条子命令模式，脚本用 printf 组合命令 + quit 退出。
afc() {
  printf '%s\n' "$@" quit | afcclient --container "$APP_ID" -u "$UDID" 2>&1
}

# 容器内文件是否已存在：ls 父目录 + 精确匹配 basename（afcclient 无 test -f）。
# 必须整列精确匹配（awk $1 == name）：容器里有 ba.mp4 时查 a.mp4，子串匹配会误判已存在，
# 注入指向不存在文件的配置 → 进态超时且排查指引全错（M2）。
afc_has() { # $1 = 容器内路径
  afc "ls ${1%/*}" 2>/dev/null | awk -v name="${1##*/}" '$1 == name {found=1} END {exit !found}'
}

# App 进程是否在跑：devicectl process list 按 bundle id 过滤（进程存在性弱判定，仅给
# --no-relaunch / 冷启动前置用；真就绪以 wait_ready 的 /source 强判定为准）。
app_running() {
  # shellcheck disable=SC2086 —— $DEVICECTL 故意不加引号："xcrun devicectl" 需分词成两个参数，
  # 与 _common.sh 的 $ADB 受控 token 惯例一致；词表不是用户输入，无注入面
  $DEVICECTL device process list --device "$UDID" 2>/dev/null \
    | awk -v app="$APP_ID" '$0 ~ app {found=1} END {exit !found}'
}

# ---- 素材 push ----
# 逐个素材落盘容器 Documents/handeye/media/<basename>，填 DEVICE_RELS（相对 Documents 段，
# 组 work_path）。设备已有则跳过；没有则要求 --media-host 并 afcclient put。
# 相对段经 is_valid_relative_path 校验（字母数字点下划线连字符），basename 无空格/中文，
# 远端名安全；但 afcclient 的 put LOCALPATH 对含空格/中文的 host 本地路径会错误分词，
# 故 push 前复制到 /tmp 的 ASCII 临时路径。
push_media() {
  DEVICE_RELS=()
  # 确保落盘目录存在（mkdir 对已存在目录会报错，逐层容忍；put 前的 ls 验证兜底）
  afc "mkdir Documents" "mkdir Documents/handeye" "mkdir Documents/$DEVICE_MEDIA_DIR" >/dev/null 2>&1 || true

  local i
  for i in "${!MEDIA_DEVICE_PATHS[@]}"; do
    local device_rel="${MEDIA_DEVICE_PATHS[$i]}"
    local base="${device_rel##*/}"
    local rel_out="$DEVICE_MEDIA_DIR/$base"     # 相对 Documents 路径（进 work_path）
    local remote="Documents/$rel_out"           # afcclient --container 下的落盘路径
    log "media[$i] device_path(相对)=$device_rel"
    log "media[$i] 落盘容器路径=$remote"

    if afc_has "$remote"; then
      log "media[$i] 容器已有素材，跳过 push"
      DEVICE_RELS+=("$rel_out")
      continue
    fi

    local host_path="${MEDIA_HOST_PATHS[$i]:-}"
    if [ -z "$host_path" ]; then
      printf '错误: media[%s] 容器无素材且未给 --media-host（无可 push 源）\n' "$i" >&2
      printf '  iOS 侧 push 由本脚本走 afcclient 完成（不经 fetch_media.sh），须显式给 host 源\n' >&2
      exit 5
    fi
    if [ ! -f "$host_path" ]; then
      printf '错误: media[%s] host 素材不存在: %s\n' "$i" "$host_path" >&2
      exit 5
    fi

    # 扩展名取自 basename：无扩展名的素材直接拒绝（设备接收端按扩展名识别媒体类型，
    # 且避免历史实现 mktemp 模板拼非法路径在 set -e 下静默 exit 1 违反 exit 5 契约，M3）
    local host_base="${host_path##*/}" ext
    case "$host_base" in
      *.*) ext="${host_base##*.}" ;;
      *)
        printf '错误: media[%s] host 素材无扩展名: %s\n' "$i" "$host_path" >&2
        printf '  设备端按扩展名识别媒体类型，请改名（如 %s.mp4）后重试\n' "$host_base" >&2
        exit 5 ;;
    esac

    # push 前先复制到 /tmp 的 ASCII 路径：afcclient put LOCALPATH 按空格切分，含中文/
    # 空格的本地路径会被错误分词甚至卡死。用 mktemp -d 建目录、目录内拼固定文件名——
    # mktemp -t 基文件 + 拼后缀会产生两个路径，基文件在每次 push 后泄漏（m1）；
    # 目录所有出口（cp 失败 / put 失败 / 成功）统一 rm -rf。
    local ascii_dir ascii_src
    ascii_dir="$(mktemp -d -t handeye_media)"
    ascii_src="$ascii_dir/media.$ext"
    if ! cp "$host_path" "$ascii_src"; then
      rm -rf "$ascii_dir"
      printf '错误: media[%s] 复制 host 素材到临时路径失败: %s\n' "$i" "$host_path" >&2
      exit 5
    fi
    log "media[$i] afcclient put '$host_path' → 容器 $remote"
    if ! afc "put -rf $ascii_src $remote" "ls Documents/$DEVICE_MEDIA_DIR" >/dev/null; then
      rm -rf "$ascii_dir"
      printf '错误: media[%s] afcclient 推送素材失败: %s\n' "$i" "$host_path" >&2
      printf '  确认: 真机已解锁信任 · App 已装（--container 依赖已装 App 才有沙盒）\n' >&2
      exit 5
    fi
    rm -rf "$ascii_dir"
    if ! afc_has "$remote"; then
      printf '错误: media[%s] push 后容器内未找到 %s（afcclient 未报错但文件缺失）\n' "$i" "$remote" >&2
      exit 5
    fi
    log "media[$i] 素材已 push: 容器 $remote"
    DEVICE_RELS+=("$rel_out")
  done
}

# ---- 配置注入 ----
# work_path 传相对 Documents 段、多素材以 `|` 连接（App 侧负责拼 Documents 根得到沙盒绝对
# 路径）；无素材时 url 不带 query，App 读不到 work_path 进默认态。
build_bootstrap_url() {
  local url="${HANDEYE_DEEPLINK_SCHEME}://bootstrap"
  if [ "${#DEVICE_RELS[@]}" -gt 0 ]; then
    local work_path_join
    work_path_join=$(printf '%s|' "${DEVICE_RELS[@]}")
    url="$url?work_path=${work_path_join%|}"
  fi
  printf '%s' "$url"
}

# 写容器 Documents/handeye_bootstrap.json，内容放 bootstrap deeplink（iOS 的注入机制：
# relaunch 后 App 冷启动完成回调自读该文件进目标态，等价 Android 的 am start deeplink）。
write_bootstrap_config() {
  local url
  url=$(build_bootstrap_url)
  log "写容器 Documents/$BOOTSTRAP_CONFIG_NAME"
  log "bootstrap url: $url"

  local tmp
  tmp="$(mktemp)"
  printf '{"url":"%s"}\n' "$url" > "$tmp"

  # 先删旧文件再 put：afcclient 的 put 覆盖已存在文件不截断，旧内容残留会污染 json
  # （多次写叠加出 `}\n<旧尾巴>`，App 解析失败 → 不进态）。
  afc "rm Documents/$BOOTSTRAP_CONFIG_NAME" >/dev/null 2>&1 || true
  if ! afc "put -rf $tmp Documents/$BOOTSTRAP_CONFIG_NAME" "ls Documents" >/dev/null; then
    rm -f "$tmp"
    printf '错误: afcclient 写配置文件失败（Documents/%s）\n' "$BOOTSTRAP_CONFIG_NAME" >&2
    printf '  确认: 真机已解锁信任 · App 已装 · 容器 Documents/ 可写\n' >&2
    exit 6
  fi
  rm -f "$tmp"
  log "配置已注入: 容器 Documents/$BOOTSTRAP_CONFIG_NAME"
}

# ---- 冷启动重拉 ----
# 让 App 重读 handeye_bootstrap.json 的唯一手段：真杀进程 + 重新 launch。
# 上游坑 4：terminate 必须真杀（SIGKILL），只退后台的热启动不会重读配置。
# devicectl device process launch 是 iOS 侧 `adb shell am start` 的等价物：走 CoreDevice
# 通道，自动 mount developer disk image（idevicedebug run 走 debugserver，依赖手工 mount
# DeveloperDiskImage 且新 iOS 版本上无匹配版本）。
cold_start() {
  log "冷启动重拉: 真杀旧进程 → launch $APP_ID"
  local pid
  # shellcheck disable=SC2086 —— $DEVICECTL 故意不加引号："xcrun devicectl" 需分词，见 app_running
  pid="$($DEVICECTL device process list --device "$UDID" 2>/dev/null \
    | awk -v app="$APP_ID" '$0 ~ app {print $1; exit}' || true)"
  if [ -n "$pid" ]; then
    log "terminate: SIGKILL pid=$pid（热启动不重读配置，必须真杀）"
    # shellcheck disable=SC2086 —— 同上，$DEVICECTL 受控 token 需分词
    $DEVICECTL device process signal --device "$UDID" --pid "$pid" --signal SIGKILL >/dev/null 2>&1 || true
  fi
  sleep 2   # 等进程回收 + 端口释放，launch 立即跟上会偶发失败
  log "launch $APP_ID"
  # shellcheck disable=SC2086 —— 同上，$DEVICECTL 受控 token 需分词
  if ! $DEVICECTL device process launch --device "$UDID" "$APP_ID" >/dev/null 2>&1; then
    printf '错误: devicectl launch 启动 App 失败\n' >&2
    printf '  确认: bundle id %s 已装真机 · 真机已解锁 · USB 已连\n' "$APP_ID" >&2
    exit 7
  fi
  log "App 已冷启动（冷启动完成回调将读 Documents/%s 进目标态）" "$BOOTSTRAP_CONFIG_NAME"
}

# ---- 隧道（后台 setup_ios.sh） ----
# setup_ios.sh 输出契约行后前台驻留持有 iproxy，直接 $(...) 捕获会永远阻塞；故后台拉起、
# 轮询其 stdout 落盘文件里的契约行。成功后该进程刻意留下继续持隧道（见文件头「隧道生命周期」）。
ensure_tunnel() {
  if [ -n "$SKIP_FORWARD" ]; then
    log "跳过 setup_ios.sh 建隧道与就绪判定（--skip-forward），隧道 / 跑批由外部编排"
    SKIP_FORWARD_SET=1
    return 0
  fi
  log "setup_ios.sh -u $UDID 后台建隧道（iproxy + /health 探测）"
  SETUP_OUT=$(mktemp)
  "$SCRIPT_DIR/setup_ios.sh" -u "$UDID" >"$SETUP_OUT" 2>&1 &
  SETUP_PID=$!

  local waited=0 hp=""
  while [ "$waited" -lt "$SETUP_TIMEOUT_SEC" ]; do
    # 进程已退出且契约行还没读到 = setup 阶段失败（iproxy 秒退 / 端口未就绪 / /health 超时）
    if ! kill -0 "$SETUP_PID" 2>/dev/null; then
      printf '错误: setup_ios.sh 进程已退出且未给出契约行（setup 阶段失败，exit 3）\n' >&2
      printf '  setup_ios.sh 输出:\n' >&2
      sed 's/^/    /' "$SETUP_OUT" >&2 || true
      exit 3
    fi
    hp=$(grep -E '^HANDEYE_HOST_PORT=' "$SETUP_OUT" 2>/dev/null | tail -1 | cut -d= -f2 || true)
    if [ -n "$hp" ]; then
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
  if [ -z "$hp" ]; then
    printf '错误: %ss 内未等到 setup_ios.sh 的 HANDEYE_HOST_PORT 契约行（setup 阶段失败）\n' "$SETUP_TIMEOUT_SEC" >&2
    sed 's/^/    /' "$SETUP_OUT" >&2 || true
    exit 3
  fi
  DEVICE_PORT=$(grep -E '^HANDEYE_DEVICE_PORT=' "$SETUP_OUT" | tail -1 | cut -d= -f2 || true)
  HOST_PORT=$hp
  BASE_URL="http://127.0.0.1:$HOST_PORT"
  log "隧道已建: BASE=$BASE_URL（device 端口 ${DEVICE_PORT:-未知}，setup_ios.sh pid=$SETUP_PID 持隧道）"
}

# ---- 进态判定 ----
# 强判定（唯一轨道）：轮询 /source?name=bootstrap 非 null。setup_ios.sh 阶段已探过 /health，
# 隧道可用即 server 在；iOS 无 top-Activity/dumpsys 弱判定等价物。
# 超时附排查指引：拉容器里的配置文件校验 JSON、看设备日志。
wait_ready() {
  if [ -n "$SKIP_FORWARD_SET" ]; then
    return 0
  fi
  log "进态判定 · ${STATE_POLL_TIMEOUT_SEC}s 内 /source?name=bootstrap 非 null 即返 0"
  local waited=0 last_src=""
  while [ "$waited" -lt "$STATE_POLL_TIMEOUT_SEC" ]; do
    last_src="$(curl -sf -m 2 "$BASE_URL/source?name=bootstrap" 2>/dev/null || true)"
    if [ -n "$last_src" ] && [ "$last_src" != "null" ]; then
      log "进态成功（等了 ${waited}s，/source?name=bootstrap 非 null）"
      return 0
    fi
    sleep "$STATE_POLL_INTERVAL_SEC"
    waited=$((waited + STATE_POLL_INTERVAL_SEC))
  done

  printf '错误: 进态超时（%ss 内 /source?name=bootstrap 仍为 null，未达成进态）\n' "$STATE_POLL_TIMEOUT_SEC" >&2
  printf '  最后响应: %s\n' "${last_src:-（无响应）}" >&2
  printf '  排查指引:\n' >&2
  printf '    1. 拉容器配置校验 JSON 合法: printf %%s\\n "get Documents/%s" quit | afcclient --container %s -u %s\n' \
    "$BOOTSTRAP_CONFIG_NAME" "$APP_ID" "$UDID" >&2
  printf '    2. 确认 App 冷启动完成回调已读 %s（热启动不重读，重跑本脚本触发真杀重拉）\n' "$BOOTSTRAP_CONFIG_NAME" >&2
  printf '    3. 看设备日志: xcrun devicectl device console --device %s（过滤 %s）\n' "$UDID" "$APP_ID" >&2
  exit 8
}

# ---- 结果输出 ----

emit_result() {
  if [ -n "$SKIP_FORWARD_SET" ]; then
    cat <<EOF

✅ 冷启动进态注入完成（--skip-forward：未建隧道、未做进态判定）
    配置: 容器 Documents/$BOOTSTRAP_CONFIG_NAME 已写入
    隧道 / 就绪判定由外部编排负责（如 run.sh --host-port 场景）
EOF
    return 0
  fi
  echo ""
  echo "HANDEYE_HOST_PORT=$HOST_PORT"
  echo "HANDEYE_DEVICE_PORT=$DEVICE_PORT"
  cat <<EOF

✅ 冷启动进态完成
    base:      $BASE_URL
    bootstrap: $(curl -sf -m 2 "$BASE_URL/source?name=bootstrap" 2>/dev/null || echo '?')

隧道由后台 setup_ios.sh（pid $SETUP_PID）驻留持有；拆除: kill $SETUP_PID
紧接可跑: ./scripts/run.sh --scenario <name> --host-port $HOST_PORT
EOF
}

# ---- 清理 ----
# EXIT trap：失败路径代杀后台 setup_ios（其 EXIT trap 会连带清掉 iproxy），不残留占用
# host 端口；成功路径 KEEP_SETUP=1 后放行——隧道刻意留用（等价 Android 侧 bootstrap 后
# adb forward 仍在）。
cleanup() {
  if [ -n "${SETUP_PID:-}" ] && [ -z "$KEEP_SETUP" ]; then
    kill "$SETUP_PID" 2>/dev/null || true
  fi
  # SETUP_OUT 是 mktemp 落盘文件，成功 / 失败 / 超时所有出口都不留（m2）
  [ -n "${SETUP_OUT:-}" ] && rm -f "$SETUP_OUT" || true
}

# ---- 自检（隐藏入口，供无设备环境验证纯 host 逻辑） ----
# HANDEYE_SELFTEST=1 ./bootstrap_ios.sh —— 校验多素材拆分 / 相对段校验 / deeplink 组装的
# 纯函数行为（对齐 run.sh 的 HANDEYE_SELFTEST 惯例；设备链路由外部 stub 工具链验证）。
selftest() {
  # split_pipe：单值 / 多值 / 尾随空段
  split_pipe "a.mp4"
  [ "${#PIPE_SPLIT_OUT[@]}" -eq 1 ] && [ "${PIPE_SPLIT_OUT[0]}" = "a.mp4" ] \
    || { echo "SELFTEST FAIL: split_pipe 单值" >&2; return 1; }
  split_pipe "a.mp4|b.mp4"
  [ "${#PIPE_SPLIT_OUT[@]}" -eq 2 ] && [ "${PIPE_SPLIT_OUT[1]}" = "b.mp4" ] \
    || { echo "SELFTEST FAIL: split_pipe 多值" >&2; return 1; }
  split_pipe "a.mp4|"
  [ "${#PIPE_SPLIT_OUT[@]}" -eq 2 ] && [ -z "${PIPE_SPLIT_OUT[1]}" ] \
    || { echo "SELFTEST FAIL: split_pipe 尾随空段" >&2; return 1; }

  # is_valid_relative_path：合法段 / 绝对路径 / .. 段（含裸 ..）/ 空段 / 非法字符 / 首尾斜杠
  is_valid_relative_path "e2e_media/a.mp4" || { echo "SELFTEST FAIL: 合法段被拒" >&2; return 1; }
  is_valid_relative_path "a-b_c.d/1.mp4"  || { echo "SELFTEST FAIL: 合法段被拒（连字符下划线点）" >&2; return 1; }
  is_valid_relative_path "/abs/a.mp4"     && { echo "SELFTEST FAIL: 绝对路径被放行" >&2; return 1; }
  is_valid_relative_path "a/../b.mp4"     && { echo "SELFTEST FAIL: .. 段被放行" >&2; return 1; }
  is_valid_relative_path ".."             && { echo "SELFTEST FAIL: 裸 .. 被放行" >&2; return 1; }
  is_valid_relative_path "a//b.mp4"       && { echo "SELFTEST FAIL: 空段被放行" >&2; return 1; }
  is_valid_relative_path "a/b mp4"        && { echo "SELFTEST FAIL: 含空格被放行" >&2; return 1; }
  is_valid_relative_path "a/"             && { echo "SELFTEST FAIL: 尾斜杠被放行" >&2; return 1; }
  is_valid_relative_path ""               && { echo "SELFTEST FAIL: 空串被放行" >&2; return 1; }

  # build_bootstrap_url：无素材 / 单素材 / 多素材 | 连接
  HANDEYE_DEEPLINK_SCHEME="handeye"
  DEVICE_RELS=()
  [ "$(build_bootstrap_url)" = "handeye://bootstrap" ] \
    || { echo "SELFTEST FAIL: 无素材 url" >&2; return 1; }
  DEVICE_RELS=("handeye/media/a.mp4")
  [ "$(build_bootstrap_url)" = "handeye://bootstrap?work_path=handeye/media/a.mp4" ] \
    || { echo "SELFTEST FAIL: 单素材 url（$(build_bootstrap_url)）" >&2; return 1; }
  DEVICE_RELS=("handeye/media/a.mp4" "handeye/media/b.mp4")
  [ "$(build_bootstrap_url)" = "handeye://bootstrap?work_path=handeye/media/a.mp4|handeye/media/b.mp4" ] \
    || { echo "SELFTEST FAIL: 多素材 url（$(build_bootstrap_url)）" >&2; return 1; }

  echo "SELFTEST OK"
  return 0
}

# ---- 主流程 ----

main() {
  parse_args "$@"
  # 配置加载放在 parse_args 之后：-h 不需要任何配置（与 bootstrap_android.sh 一致）
  load_config
  check_prerequisites
  resolve_udid

  run_hook pre-bootstrap

  # 构建 + 安装（含新鲜度判定；--skip-build --skip-install 时对方直接短路返回）
  build_and_install

  # 素材 push + 配置注入（iOS 的 deeplink 注入等价物：文件写进沙盒，冷启动自读）
  if [ "${#MEDIA_DEVICE_PATHS[@]}" -gt 0 ]; then
    push_media
  fi
  write_bootstrap_config

  # 冷启动重拉（真杀 + launch，保证重读配置）
  if [ -z "$NO_RELAUNCH" ]; then
    cold_start
  else
    log "跳过冷启动重拉（--no-relaunch），接入已在跑的 App"
    if ! app_running; then
      die "--no-relaunch 但 App 进程不在（$APP_ID），去掉 --no-relaunch 或先手动启动 App" 7
    fi
  fi

  trap cleanup EXIT
  ensure_tunnel
  wait_ready
  run_hook post-bootstrap
  # 成功：留下后台 setup_ios.sh 持隧道（见文件头「隧道生命周期」）
  KEEP_SETUP=1
  emit_result
}

if [ "${HANDEYE_SELFTEST:-}" = "1" ]; then
  selftest
  exit $?
fi

main "$@"
