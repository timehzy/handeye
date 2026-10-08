#!/usr/bin/env bash
# handeye e2e 总控入口（v1 · Android 全链路）
#
# 流程：拉 bootstrap plan（scenario → page/source）→ 按 (page,source) 稳定分组 →
#       逐组 bootstrap（委托 bootstrap_android.sh：build/install + 冷启动 + deeplink 进态 +
#       建隧道，输出 HANDEYE_HOST_PORT 契约行）→ setup 复用 bootstrap 的隧道（/health 兜底重探）→
#       分组跑 orchestrator → 汇总表收尾。
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
#   --check-integration  软诊断：有效配置与三级来源 / 工具链 / Manifest deeplink scheme /
#                        App 安装态与进程 / iOS Podfile 本地依赖，逐项 [ok]/[warn]/[info] 报告，
#                        只提示不阻断（exit 恒 0；配置缺失 / java 缺失按前置缺失 exit 4）
#   snapshot diff <a.json> <b.json>
#                        对比两份 snapshot——纯 host 侧工具，不需要设备 / adb forward，
#                        直接把参数透传给 orchestrator（:demo-android:e2e:run）。
#
# 分组 bootstrap（结构对齐上游 e2e.sh [2/3] 段）:
#   每组 bootstrap 状态三个全局量：LAST_BOOTSTRAP_KEY（本 run 内最近一次成功 bootstrap 的
#   page<TAB>source，同 key 直接跳过）、FRESHNESS_JUDGED / INSTALL_FLAGS（新鲜度只判定一次、
#   结论复用——实际判定在 build_install_android.sh 内部完成，首组传空 flags 让其内判，
#   成功后置 "--skip-build --skip-install"，后续组只重启不重装）。
#   page=demo：bootstrap 整体委托 bootstrap_android.sh（N2 已落地，替代 v1 内联
#   build+am-start fast-path）——build/install、冷启动、deeplink 进态、建隧道都归它；
#   组素材 source 列以 --media-path 透传（多素材 | 连接与其 split_pipe 语义一致，plan 反查
#   已保证相对段合法），设备已有素材即跳过 push，缺素材时它以 exit 5 清晰早报。
#   source 列含 host/smb 段的 fetch_media 自动拉取（Task 14）落地前，缺素材的组会判失败，
#   不影响其它组。
#
# 前置:
#   - Android device 已连接（adb devices 可见），debug 变体 App 可启动
#   - JDK 17（缺时 macOS: /usr/libexec/java_home -v 17 查看已装版本；或 brew install openjdk@17）
#
# 退出码:
#   0  全部场景通过；或 -i 模式下用户键入 q 主动退出；--check-integration 诊断完成
#      （软诊断：任一项缺失也只报告不阻断，exit 恒 0——配置缺失 / java 缺失例外，exit 4）
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
  # 路径用 \" 包进 --args：gradle 拆分参数时剥离这层引号，带空格路径才不会被切断
  exec ./gradlew :demo-android:e2e:run --args="snapshot diff \"$1\" \"$2\"" \
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
      # 带值 flag：$# 不足 2 时 shift 2 会在 set -e 下静默 exit 1，这里显式判空报参数错（exit 2）
      --scenario)  [ -n "${2:-}" ] || die "参数 --scenario 缺少值" 2; SCENARIO="$2"; shift 2 ;;
      --tag)       [ -n "${2:-}" ] || die "参数 --tag 缺少值" 2; TAG="$2"; shift 2 ;;
      --all)       ALL=1; shift 1 ;;
      --platform)  [ -n "${2:-}" ] || die "参数 --platform 缺少值" 2; PLATFORM="$2"; shift 2 ;;
      --serial)    [ -n "${2:-}" ] || die "参数 --serial 缺少值" 2; SERIAL="$2"; shift 2 ;;
      --host-port) [ -n "${2:-}" ] || die "参数 --host-port 缺少值" 2; USER_HOST_PORT="$2"; shift 2 ;;
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

# ---- --check-integration：软诊断，只报告不阻断 ----
# spec §5.1：「软前置」的可选诊断——exit 恒 0（adb 缺失 / App 未装 / scheme 未注册都只打
# [warn]/[info]），例外只有两个硬前置：配置缺失（load_config 的 require_vars）与 java 缺失
# （工具链），按既有约定 exit 4。adb 缺失不判 exit 4——诊断的意义就在于环境不全时也能
# 把能查的都查了（Manifest scheme 是纯离线的，不依赖设备）。

ci_ok()   { printf '[ok]   %s\n' "$*"; }
ci_warn() { printf '[warn] %s\n' "$*" >&2; }
ci_info() { printf '[info] %s\n' "$*"; }

# 配置来源归因（三级：env = 环境变量 > local = handeye.local.sh > example = 默认值）。
# load_config 之后所有变量必有值，归因靠回溯三个来源：先看 load_config 之前的原始
# 环境（快照，见 check_integration），再 grep local.sh 赋值行，都不是即 example 默认。
# 已知边界（不修）：local.sh 非 export 变量会覆写已导出的 env 值（_common.sh source
# 顺序的既定交互），此时归因仍标 [env]、显示的却是 local 值——local.sh 覆写已导出
# 环境变量属于配置误用，提示意义已足够。
config_source() { # $1 = 变量名 → stdout: env / local / example
  local var="$1" local_file="$HANDEYE_ROOT/scripts/handeye.local.sh"
  # [env] 以 load_config 之前的原始环境快照为准：local.sh 里 `export VAR=` 会把变量
  # 并进真实环境，事后 printenv 无法区分「env 带来」还是「local.sh 导出」
  if [ -n "${CI_ORIG_ENV:-}" ] && printf '%s\n' "$CI_ORIG_ENV" | grep -qx -- "$var"; then
    printf 'env'; return
  fi
  # local.sh 赋值两种写法都认：`VAR=x`（可带 export 前缀）与 `: "${VAR:=x}"`。
  # 行首锚定 + 变量名后紧跟赋值符，天然排除 HANDEYE_FOO 命中 HANDEYE_FOO_BAR /
  # OTHER_HANDEYE_FOO 的边界误报
  if [ -f "$local_file" ] \
    && { grep -qE "^[[:space:]]*(export[[:space:]]+)?${var}=" "$local_file" \
      || grep -qE "^[[:space:]]*: \"\\\${${var}:=" "$local_file"; }; then
    printf 'local'; return
  fi
  printf 'example'
}

# 打印解析后的有效配置，逐项标注三级来源。
print_effective_config() {
  local var src
  ci_info "有效配置（来源标注：[env]=环境变量 > [local]=handeye.local.sh > [example]=默认值）"
  for var in APP_ID HANDEYE_DEEPLINK_SCHEME ANDROID_GRADLE_TASK ANDROID_MAIN_ACTIVITY \
             ANDROID_SERIAL IOS_PROJECT_DIR IOS_SCHEME FIXTURES_JSON; do
    src=$(config_source "$var")
    printf '    [%-7s] %-22s = %s\n' "$src" "$var" "${!var:-<空>}"
  done
}

# 在 $ANDROID_HOME/build-tools（未设则回退常见 SDK 安装路径）下探测 aapt：
# 候选目录按版本号排序（sort -V），取首个含可执行 aapt 的。
# 已知限制：路径含空格的 SDK 安装位置探测不到（for 循环按空白分词），需用 ANDROID_HOME 无空格路径或软链规避。
find_aapt() {
  local d best=""
  for d in $( { [ -n "${ANDROID_HOME:-}" ] && ls -d "$ANDROID_HOME/build-tools"/*; \
                ls -d "$HOME/Library/Android/sdk/build-tools"/* \
                     "$HOME/Android/Sdk/build-tools"/*; } 2>/dev/null | sort -V ); do
    if [ -z "$best" ] && [ -x "$d/aapt" ]; then best="$d/aapt"; fi
  done
  [ -n "$best" ] && printf '%s' "$best"
}

# Android 集成态：Manifest deeplink scheme（aapt 离线探测）+ 安装态 + 进程存活。
# adb 不可用（不在 PATH / 无在线设备）时逐项降级为 [warn]/[info] 跳过，不影响 exit 0。
ci_android() {
  local apk aapt dump
  # Manifest scheme 探测：badging 不输出 intent-filter（实测 build-tools 36.x），badging
  # 里没有 scheme 行时回退 xmltree 全量 manifest；xmltree 里 label 可能含 scheme 字符串，
  # 只认 android:scheme 属性行，避免「handeye demo」这类误报。
  apk=$(ls -t $HANDEYE_ROOT/$ANDROID_APK_GLOB 2>/dev/null | head -1)
  if [ -z "$apk" ]; then
    ci_info "未找到已构建 APK（$ANDROID_APK_GLOB），跳过 deeplink scheme 检查（先跑一次 build）"
  elif ! aapt=$(find_aapt); then
    ci_info "跳过 scheme 检查（未找到 aapt；安装 Android SDK build-tools 或设置 ANDROID_HOME）"
  else
    dump=$("$aapt" dump badging "$apk" 2>/dev/null | grep -i "scheme" || true)
    if [ -z "$dump" ]; then
      # xmltree 全量 manifest：label 可能含 scheme 字符串，只认 android:scheme 属性行
      dump=$("$aapt" dump xmltree "$apk" AndroidManifest.xml 2>/dev/null | grep "android:scheme" || true)
    fi
    if printf '%s' "$dump" | grep -qF -- "$HANDEYE_DEEPLINK_SCHEME"; then
      ci_ok "Manifest 已注册 deeplink intent-filter（scheme: $HANDEYE_DEEPLINK_SCHEME）· $apk"
    else
      ci_warn "Manifest 未注册 deeplink intent-filter（scheme: $HANDEYE_DEEPLINK_SCHEME），见 docs/integration-points.md"
    fi
  fi

  # 安装态 + 进程存活（需要 adb 与在线设备；诊断模式不复用 resolve_device——它无设备时
  # die exit 4，与「软诊断不阻断」矛盾，这里自行做软设备选择）
  if ! command -v adb >/dev/null 2>&1; then
    ci_warn "adb 不在 PATH（安装 Android SDK platform-tools），跳过设备 / 安装态 / 进程检查"
    return 0
  fi
  local devices sel="" apk_path pid
  devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ -z "$devices" ]; then
    ci_warn "adb devices 无在线设备，跳过安装态 / 进程检查（连机后重跑本诊断）"
    return 0
  fi
  if [ -n "$SERIAL" ]; then sel="$SERIAL"
  elif [ -n "${ANDROID_SERIAL:-}" ]; then sel="$ANDROID_SERIAL"
  else
    sel=$(printf '%s\n' "$devices" | head -1)
    if [ "$(printf '%s\n' "$devices" | grep -c .)" -gt 1 ]; then
      ci_warn "检测到多台在线设备且未指定（--serial / ANDROID_SERIAL），诊断用第一台: $sel"
    fi
  fi
  ADB="adb ${sel:+-s $sel}"
  ci_ok "目标设备: $sel"

  apk_path=$($ADB shell pm path "$APP_ID" 2>/dev/null | head -1 | tr -d '\r')
  if [ -z "$apk_path" ]; then
    ci_warn "App 未安装: $APP_ID（bootstrap 阶段会自动 build+install，或先跑 $SCRIPT_DIR/build_install_android.sh）"
  else
    ci_ok "App 已安装: $APP_ID（${apk_path}）"
  fi
  pid=$($ADB shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | head -1)
  if [ -z "$pid" ]; then
    ci_info "App 进程未存活（bootstrap 冷启动会拉起，非问题）"
  else
    ci_ok "App 进程存活: pid=$pid"
  fi
}

# iOS 集成态：Podfile 本地 framework 依赖（:path pod）。AppDelegate/SceneDelegate 是否读取
# handeye_bootstrap.json 不做代码扫描（太侵入），指路文档。
ci_ios() {
  local dir="$IOS_PROJECT_DIR" podfile
  case "$dir" in /*) ;; *) dir="$HANDEYE_ROOT/$dir" ;; esac
  dir="${dir%/}"; [ -n "$dir" ] || dir="/"   # 去尾斜杠（example 默认 demo-ios/），防双斜杠路径
  podfile="$dir/Podfile"
  if [ ! -d "$dir" ]; then
    ci_warn "IOS_PROJECT_DIR 不存在: $dir（iOS 接入方式见 docs/integration-points.md）"
  elif [ ! -f "$podfile" ]; then
    ci_info "未找到 Podfile: $podfile（非 CocoaPods 工程？iOS 接入方式见 docs/integration-points.md）"
  elif grep -q ":path" "$podfile"; then
    ci_info "Podfile 检测到本地 framework 依赖（:path pod）: $podfile"
  else
    ci_warn "Podfile 未检测到本地 framework 依赖；若走 KMP framework 接入，参考 docs/samples/ios-podfile-local.rb"
  fi
  ci_info "AppDelegate / SceneDelegate 是否读取 handeye_bootstrap.json 不做代码扫描，接入点见 docs/integration-points.md"
}

check_integration() {
  log "check-integration（$PLATFORM）：软诊断逐项报告，不阻断执行"
  # 归因基准：load_config source local.sh 之前的原始环境变量名快照——local.sh 里
  # `export VAR=` 会把变量并进真实环境，事后 printenv 分不清来源（config_source 消费）
  CI_ORIG_ENV=$(printenv | cut -d= -f1 | sort)
  # 硬前置例外 1：配置缺失（load_config → require_vars 失败 exit 4）
  load_config
  # 硬前置例外 2：java 缺失（工具链；版本非 17 只警告，check_jdk 内已处理）
  if ! command -v java >/dev/null 2>&1; then
    printf '缺少 java（JDK 17 — macOS 查看已装: /usr/libexec/java_home -v 17；安装: brew install openjdk@17）\n' >&2
    exit 4
  fi
  check_jdk

  print_effective_config
  if [ "$PLATFORM" = "android" ]; then
    # adb / curl / jq 缺失只降级为提示（软诊断），不阻断
    command -v curl >/dev/null 2>&1 || ci_warn "curl 不在 PATH（macOS 自带，PATH 异常？）"
    command -v jq   >/dev/null 2>&1 || ci_warn "jq 不在 PATH（brew install jq）"
    ci_android
  else
    ci_ios
  fi
  log "以上均为提示，不阻断执行"
  exit 0
}

# ---- [1/3] 拉 bootstrap plan ----
# 契约行：name<TAB>page<TAB>source<TAB>host_path<TAB>smb_path（详见 BootstrapPlanCli）。
# 同时承担 scenario/tag 合法性校验（未注册在这里就报错，不等跑批）。

gradle_plan() { # $1 = selector 串（如 "--all" / "--tag smoke" / "select_ratio"）
  # $1 是脚本内构造的受控 token（selector 语义含空格，如 "--tag smoke"），故意分词展开
  # shellcheck disable=SC2086
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

# page=demo bootstrap：整体委托 bootstrap_android.sh（N2 已落地，替代 v1 内联
# build+am-start fast-path）。素材/换页/草稿的细粒度判定、冷启动、deeplink 注入、
# 端口隧道都归那个脚本；run.sh 只传开关、解析契约行、维护 INSTALL_FLAGS 复用态。
# 上游 ensure_bootstrap_main 对应段。
ensure_bootstrap_demo() { # $1 = source（plan source 列：沙盒相对段，多素材 | 连接，可空）
  local source="$1"
  local args=() out rc=0 hp

  # 新鲜度只判定一次、结论复用：首组把判定交给 build_install_android.sh 内部
  # （FORCE_REINSTALL 经环境变量透传，--force-reinstall 语义一致）；
  if [ "$FRESHNESS_JUDGED" -eq 0 ]; then
    FRESHNESS_JUDGED=1
    INSTALL_FLAGS=""
  fi

  # 组素材需求透传 --media-path（plan source 列与 bootstrap split_pipe 语义一致：多素材
  # | 连接；plan 反查阶段已校验相对段合法性）。设备已有素材时 bootstrap 内部跳过 push，
  # 缺素材且 fetch_media.sh（Task 14）未落地时它以 exit 5 清晰早报（本组判失败，不影响其它组）。
  [ -z "$source" ] || args+=("--media-path" "$source")
  # 多机时必须把选中的设备传下去（bootstrap 内部 resolve_device 同理，双保险）。
  # 用 || 而非 && 短路：SERIAL 为空时整条 && 列表返回非零，在 set -e 函数体内会误杀脚本
  [ -z "$SERIAL" ] || args+=("-s" "$SERIAL")

  log "bootstrap: bootstrap_android.sh $INSTALL_FLAGS ${args[*]:-}"
  # $INSTALL_FLAGS 是脚本内构造的受控 token（空 / "--skip-build --skip-install"），需词分割展开
  # shellcheck disable=SC2086
  # 已知限制：stdout 被整体捕获、批末回放，stderr 实时穿透——bootstrap 失败时日志时间线倒置
  out=$("$SCRIPT_DIR/bootstrap_android.sh" $INSTALL_FLAGS ${args[@]+"${args[@]}"}) || rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -ne 0 ]; then
    warn "bootstrap_android.sh 退出码 $rc（page=demo），本组跳过"
    case "$rc" in
      3) warn "setup 阶段失败（隧道形式化）" ;;
      4) warn "前置缺失 / debug server 端口反查失败" ;;
      5) warn "素材 push 失败：设备沙盒缺素材且 fetch_media.sh 未落地（Task 14）；先把素材放到设备或配好 catalog 源路径" ;;
      6) warn "deeplink 注入失败：Manifest 是否注册了 \$HANDEYE_DEEPLINK_SCHEME intent-filter？见 docs/integration-points.md" ;;
      7) warn "冷启动失败或秒崩（am start 失败 / 20s 内进程未出现）" ;;
      8) warn "进态超时（Track A/B 均未通过）" ;;
      *) warn "未预期的退出码（见 bootstrap_android.sh 头部退出码表）" ;;
    esac
    return 1
  fi

  # 解析契约行 HANDEYE_HOST_PORT。bootstrap 已把隧道形式化（含 /health 探测），其契约即
  # 本组隧道真相源——setup 阶段（ensure_setup_and_health）见 SETUP_DONE=1 直接复用，不再
  # 二次 setup；仅 /health 重探失败时才走 do_setup 重建（App 换端口重启的兜底路径）。
  # sed 数字校验与 do_setup 路径同款；契约行被破坏（非数字）是 harness 自身 bug，直接 die
  hp=$(printf '%s\n' "$out" | grep -E '^HANDEYE_HOST_PORT=' | tail -1 | sed -E 's/^HANDEYE_HOST_PORT=([0-9]+).*/\1/')
  case "$hp" in
    '')
      warn "bootstrap 输出缺少 HANDEYE_HOST_PORT 契约行，无法确定本组 BASE_URL"
      return 1 ;;
    *[!0-9]*)
      # 契约行被破坏（非数字）是 harness 自身 bug，与「本组失败」语义不同，直接 die
      die "bootstrap 契约行 HANDEYE_HOST_PORT 非法（'$hp'）——bootstrap_android.sh 输出契约被破坏" ;;
  esac
  if [ -n "$USER_HOST_PORT" ]; then
    # --host-port：隧道归用户管。bootstrap 自建隧道仅作它的注入通道，跑批仍探用户端口；
    # 不动 HOST_PORT/FORWARD_CREATED（cleanup 不应去拆用户建的 forward）
    log "bootstrap 契约端口 $hp；--host-port 已指定，跑批仍用用户隧道 tcp:$USER_HOST_PORT"
  else
    HOST_PORT="$hp"
    BASE_URL="http://127.0.0.1:$HOST_PORT"
    FORWARD_CREATED=1
    SETUP_DONE=1
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
# 已知限制：多 key 组会多次 adb forward 累积（每组各自端口），卸载/重装设备后如端口
# 异常，需手动 `adb forward --remove-all` 清理。

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
    # 故意用裸 adb 而非 $ADB：adb forward --remove 是 host 侧操作，与设备无关，但需要
    # preflight 里 export 的 ANDROID_SERIAL 才能在多机时命中同一台设备——这是隐式耦合，
    # 别为了「统一风格」改成 $ADB（cleanup 时 $ADB 变量可能已不可用，且语义本就不依赖 -s）
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
# 已知限制：gradle 子进程 stdout/stderr 合流后经 tee 回放，两端都指向终端时交错顺序可能乱序（内容不丢）。

ART_DIR=""
LOG_FILE=""

run_group_scenarios() { # $1 = 逗号分隔 members
  local members="$1"
  log "跑批: gradlew :demo-android:e2e:run --args=\"$members $BASE_URL\""
  local pipe rc
  # 调用形态是 `run_group_scenarios ... || group_ok=1`（|| 列表内）——errexit 在函数体内
  # 全程被抑制，gradle 管道非零退出不会误杀脚本，无需靠 PIPESTATUS 之外的额外保护
  ( cd "$HANDEYE_ROOT" && ./gradlew :demo-android:e2e:run --args="$members $BASE_URL" \
      -Dorg.gradle.configuration-cache=false --console=plain ) 2>&1 | tee -a "$LOG_FILE"
  # PIPESTATUS 必须在管道之后立即取：tee 掩盖了 gradle 退出码（坑 5）
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
    # 行内分隔用 TAB：source 可能含多素材 `|` 连接（plan 契约列），用 | 会冲断列解析
    SUMMARY="${SUMMARY:+$SUMMARY
}组$g$TAB$page$TAB${source:-<默认>}$TAB$name$TAB$res"
  done
}

print_summary() {
  echo ""
  log "汇总（$ART_DIR/run.log）"
  printf '%-6s %-12s %-22s %-28s %s\n' 组 page source 场景 结果
  printf '%s\n' "$SUMMARY" | awk -F'\t' '{printf "%-6s %-12s %-22s %-28s %s\n", $1, $2, $3, $4, $5}'
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
# HANDEYE_SELFTEST=1 ./run.sh —— fixture 驱动：分组解析 / 汇总结果回填 / events tailer /
# ensure_bootstrap_demo 接线（stub bootstrap_android.sh 验证参数透传 + 契约行解析 + 复用态）。
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

  # 汇总回填：PASS 契约行 / FAIL 契约行 / orchestrator 合成异常行 / quit 未跑。
  # 行内分隔是 TAB（source 可能含多素材 | 连接，| 会冲断列解析）
  logf=$(mktemp)
  printf '[PASS] alpha (12ms)\n[FAIL] beta (30ms)\n[FAIL] \345\234\272\346\231\257 gamma \345\244\261\350\264\245: IOException: boom\n==> Summary: 1/3 passed\n' > "$logf"
  SUMMARY=""
  record_group_result 1 demo "" "alpha,beta,gamma,delta" "$logf" ""
  printf '%s\n' "$SUMMARY" | grep -qF "组1${TAB}demo${TAB}<默认>${TAB}alpha${TAB}PASS" || { echo "SELFTEST FAIL: 汇总 alpha" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF "组1${TAB}demo${TAB}<默认>${TAB}beta${TAB}FAIL" || { echo "SELFTEST FAIL: 汇总 beta" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF "组1${TAB}demo${TAB}<默认>${TAB}gamma${TAB}FAIL" || { echo "SELFTEST FAIL: 汇总 gamma（合成异常行未识别）" >&2; rm -f "$logf"; return 1; }
  printf '%s\n' "$SUMMARY" | grep -qF "组1${TAB}demo${TAB}<默认>${TAB}delta${TAB}未跑" || { echo "SELFTEST FAIL: 汇总 delta（quit 未跑应标未跑）" >&2; rm -f "$logf"; return 1; }

  # 多素材 source（含 |）不回冲汇总列：source 整列保留、结果列仍正确
  printf '[PASS] multi (7ms)\n' > "$logf"
  SUMMARY=""
  record_group_result 2 demo "e2e_media/a.mp4|e2e_media/b.mp4" "multi" "$logf" ""
  [ "$(printf '%s\n' "$SUMMARY" | awk -F"$TAB" '$4=="multi"{print $3}')" = "e2e_media/a.mp4|e2e_media/b.mp4" ] \
    || { echo "SELFTEST FAIL: 多素材 source 列被冲断（$SUMMARY）" >&2; rm -f "$logf"; return 1; }
  [ "$(printf '%s\n' "$SUMMARY" | awk -F"$TAB" '$4=="multi"{print $5}')" = "PASS" ] \
    || { echo "SELFTEST FAIL: 多素材 source 结果列错位（$SUMMARY）" >&2; rm -f "$logf"; return 1; }
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

  # ensure_bootstrap_demo 接线：stub bootstrap_android.sh 记录 argv、按 env 决定输出/退出码，
  # 验证 ①source 透传 --media-path ②-s 透传 ③HANDEYE_HOST_PORT 契约行 → BASE_URL/SETUP_DONE
  # ④INSTALL_FLAGS 复用态 ⑤非零退出 → 本组失败且不污染复用态 ⑥缺契约行 → 失败
  local stub_dir old_script_dir
  stub_dir=$(mktemp -d)
  cat > "$stub_dir/bootstrap_android.sh" <<'STUB'
#!/bin/sh
printf '%s\n' "$@" > "$STUB_ARGS_FILE"
if [ "${STUB_RC:-0}" != "0" ]; then exit "$STUB_RC"; fi
if [ -z "${STUB_NO_CONTRACT:-}" ]; then
  echo "HANDEYE_HOST_PORT=40321"
  echo "HANDEYE_DEVICE_PORT=40321"
fi
STUB
  chmod +x "$stub_dir/bootstrap_android.sh"
  old_script_dir=$SCRIPT_DIR
  SCRIPT_DIR="$stub_dir"
  export STUB_ARGS_FILE="$stub_dir/args1"
  # 复位 ensure_bootstrap_demo 消费的全局态（局部于本自检进程，无跨进程影响）
  FRESHNESS_JUDGED=0; INSTALL_FLAGS=""; LAST_BOOTSTRAP_KEY=""
  SERIAL="TESTSERIAL"; USER_HOST_PORT=""
  HOST_PORT=""; BASE_URL=""; FORWARD_CREATED=0; SETUP_DONE=0
  ensure_bootstrap_demo "e2e_media/a.mp4|e2e_media/b.mp4" >/dev/null \
    || { echo "SELFTEST FAIL: ensure_bootstrap_demo 成功路径返回非 0" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  grep -qx -- "--media-path" "$stub_dir/args1" && grep -qx -- "e2e_media/a.mp4|e2e_media/b.mp4" "$stub_dir/args1" \
    || { echo "SELFTEST FAIL: --media-path 透传丢失（$(cat "$stub_dir/args1")）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  grep -qx -- "-s" "$stub_dir/args1" && grep -qx -- "TESTSERIAL" "$stub_dir/args1" \
    || { echo "SELFTEST FAIL: -s serial 透传丢失" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  [ "$BASE_URL" = "http://127.0.0.1:40321" ] && [ "$HOST_PORT" = "40321" ] \
    && [ "$SETUP_DONE" = "1" ] && [ "$FORWARD_CREATED" = "1" ] \
    || { echo "SELFTEST FAIL: 契约行解析/BASE_URL/SETUP_DONE（base=$BASE_URL setup=$SETUP_DONE）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  [ "$INSTALL_FLAGS" = "--skip-build --skip-install" ] \
    || { echo "SELFTEST FAIL: INSTALL_FLAGS 未置复用态（'$INSTALL_FLAGS'）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }

  # 失败路径：退出码 6 → 返回 1 且复用态不置位。SERIAL 置空走一遍，覆盖空 serial
  # 分支（args 不加 -s；旧 && 写法在此场景会触发 set -e 误杀，回归防护）
  export STUB_ARGS_FILE="$stub_dir/args2"; export STUB_RC=6; unset STUB_NO_CONTRACT || true
  INSTALL_FLAGS=""; SERIAL=""
  if ensure_bootstrap_demo "" >/dev/null 2>&1; then
    echo "SELFTEST FAIL: bootstrap 失败路径应返回非 0" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1
  fi
  [ "$INSTALL_FLAGS" = "" ] \
    || { echo "SELFTEST FAIL: 失败路径 INSTALL_FLAGS 被污染（'$INSTALL_FLAGS'）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  if grep -qx -- "-s" "$stub_dir/args2"; then
    echo "SELFTEST FAIL: SERIAL 为空时不应透传 -s" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1
  fi

  # 缺契约行 → 失败（exit 0 但无 HANDEYE_HOST_PORT）
  export STUB_ARGS_FILE="$stub_dir/args3"; export STUB_RC=0; export STUB_NO_CONTRACT=1
  if ensure_bootstrap_demo "" >/dev/null 2>&1; then
    echo "SELFTEST FAIL: 缺契约行应返回非 0" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1
  fi

  # --host-port 分支：bootstrap 契约端口不覆盖用户隧道全局态（HOST_PORT/BASE_URL/
  # FORWARD_CREATED/SETUP_DONE 全保持原值），INSTALL_FLAGS 复用态照常置位
  export STUB_ARGS_FILE="$stub_dir/args4"; export STUB_RC=0; unset STUB_NO_CONTRACT || true
  FRESHNESS_JUDGED=1; INSTALL_FLAGS=""
  SERIAL="TESTSERIAL"; USER_HOST_PORT="45678"
  HOST_PORT=""; BASE_URL=""; FORWARD_CREATED=0; SETUP_DONE=0
  ensure_bootstrap_demo "" >/dev/null \
    || { echo "SELFTEST FAIL: --host-port 分支成功路径返回非 0" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  { [ -z "$HOST_PORT" ] && [ -z "$BASE_URL" ] && [ "$FORWARD_CREATED" = "0" ] && [ "$SETUP_DONE" = "0" ]; } \
    || { echo "SELFTEST FAIL: --host-port 分支污染了用户隧道全局态（host=$HOST_PORT base=$BASE_URL fwd=$FORWARD_CREATED setup=$SETUP_DONE）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  [ "$INSTALL_FLAGS" = "--skip-build --skip-install" ] \
    || { echo "SELFTEST FAIL: --host-port 分支 INSTALL_FLAGS 未置复用态（'$INSTALL_FLAGS'）" >&2; SCRIPT_DIR=$old_script_dir; rm -rf "$stub_dir"; return 1; }
  SCRIPT_DIR=$old_script_dir
  rm -rf "$stub_dir"
  FRESHNESS_JUDGED=0; INSTALL_FLAGS=""; LAST_BOOTSTRAP_KEY=""
  SERIAL=""; USER_HOST_PORT=""
  HOST_PORT=""; BASE_URL=""; FORWARD_CREATED=0; SETUP_DONE=0

  echo "SELFTEST OK"
  return 0
}

if [ "${HANDEYE_SELFTEST:-}" = "1" ]; then
  selftest
  exit $?
fi

main "$@"
