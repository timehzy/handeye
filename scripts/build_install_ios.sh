#!/usr/bin/env bash
# handeye · iOS 构建 + 安装（single source of truth，run.sh 与 bootstrap_ios.sh 共用）
#
# build / install 逻辑只维护这一份：xcodebuild 编 Debug 包到固定 DerivedData，
# 从 DerivedData 定位 .app，devicectl 装到目标真机。
#
# 用法:
#   ./build_install_ios.sh [--skip-build] [--skip-install] [-u udid]
#
# 参数:
#   --skip-build      跳过 xcodebuild（复用 DerivedData 里已有的 .app）
#   --skip-install    跳过 devicectl install（.app 已装到真机 / 只出包）
#   -u udid           指定目标真机 udid；缺省取 $HANDEYE_UDID / fixtures 的
#                     .devices.ios_udid / idevice_id -l 第一台（多机时须显式指定，
#                     idevice_id 输出排序不可控，取第一台可能取错设备）。
#                     仅 --skip-install 且全链路都解析不到 udid 时，构建 destination
#                     回退 generic/platform=iOS（不指定具体真机）。
#   -h, --help        打帮助
#
# 配置项（环境变量 > scripts/handeye.local.sh > handeye.example.sh）:
#   IOS_PROJECT_DIR   iOS 接入工程目录（含 xcodeproj/workspace 与 Podfile）
#   IOS_SCHEME        xcodebuild -scheme（默认 handeye-demo）
#   IOS_WORKSPACE     非空用 -workspace，否则自动探测 *.xcodeproj
#   IOS_DERIVED_DATA  固定 DerivedData 目录（默认 $HANDEYE_ROOT/build/derived-data/ios）
#
# 前置（硬检查，缺失 exit 4 并逐项给安装指引）:
#   xcodebuild（--skip-build 时不查）· devicectl · 真机 udid
#   jq 是惰性依赖：仅 fixtures 解析 udid 分支需要；缺失不导致 exit 4，该分支自动跳过
#   （用 -u / HANDEYE_UDID / idevice_id 仍可解析到设备）
#
# 输出契约（供 run.sh 消费，解析 stdout 里的 KEY=VALUE 行）:
#   HANDEYE_APP_PATH=<.app 路径>   build 成功（emit 在 post-build 钩子之后）
#                                  或 --skip-build 复用已有后 emit
#
# 软前置（不阻断）:
#   构建成功后探测工程目录的 Podfile：含 :path 本地 pod 打 info；否则打 warn
#   指路 docs/integration-points.md（iOS 接入路线与 Podfile 注入样例）。
#
# 退出码:
#   0  成功（build+install 完成 / 单边跳过完成 / 双 skip 直接返回）
#   2  参数错误
#   4  前置缺失：依赖工具（xcodebuild / devicectl）、iOS 工程目录未落地
#      （device-ios 二期）、无 *.xcodeproj/workspace、无可用真机 udid
#   9  xcodebuild 失败，或 build/skip-build 后 DerivedData 里找不到 .app 产物
#   10 devicectl install 失败
#   钩子（pre/post build/install）失败时：脚本以钩子自身的退出码终止（run_hook 不吞错）
set -eu

. "$(dirname "$0")/_common.sh"

SKIP_BUILD=""
SKIP_INSTALL=""
UDID=""
APP_PATH=""
PROJECT_DIR_ABS=""
DERIVED_DATA=""

# ---- 参数解析 ----

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --skip-build)    SKIP_BUILD=1; shift 1 ;;
      --skip-install)  SKIP_INSTALL=1; shift 1 ;;
      -u)
        shift 1
        [ $# -gt 0 ] || { printf '错误: -u 需要 udid 参数\n' >&2; exit 2; }
        UDID="$1"; shift 1 ;;
      -h|--help)       usage_from_header "$0" && exit 0 ;;
      *) printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
    esac
  done
}

# ---- udid 解析 ----
# 优先级：-u 参数 > $HANDEYE_UDID > fixtures 的 .devices.ios_udid（jq 读取）>
# idevice_id -l 第一台。jq 惰性：只有走到 fixtures 分支且 jq 在 PATH 才用；
# 全链路都解析不到时返回 1（调用方按是否必须安装决定回退还是 exit 4）。
resolve_udid() {
  if [ -n "$UDID" ]; then
    log "目标设备（-u 参数）: $UDID"
    return 0
  fi
  if [ -n "${HANDEYE_UDID:-}" ]; then
    UDID=$HANDEYE_UDID
    log "目标设备（HANDEYE_UDID）: $UDID"
    return 0
  fi
  if command -v jq >/dev/null 2>&1; then
    UDID=$(jq -r '.devices.ios_udid // ""' "$FIXTURES_JSON" 2>/dev/null || true)
    if [ -n "$UDID" ]; then
      log "目标设备（fixtures .devices.ios_udid）: $UDID"
      return 0
    fi
  fi
  if command -v idevice_id >/dev/null 2>&1; then
    local devices count
    devices=$(idevice_id -l 2>/dev/null || true)
    count=$(printf '%s\n' "$devices" | awk 'NF {n++} END {print n+0}')
    if [ "$count" -gt 0 ]; then
      if [ "$count" -gt 1 ]; then
        warn "检测到 $count 台设备且未指定（-u / HANDEYE_UDID / fixtures），idevice_id 输出排序不可控，使用第一台——多机场景请显式指定以免取错设备"
      fi
      UDID=$(printf '%s\n' "$devices" | head -1)
      log "目标设备（idevice_id -l 第一台）: $UDID"
      return 0
    fi
  fi
  UDID=""
  return 1
}

# ---- 工程目录 / workspace / xcodeproj 解析 ----

# 解析 $IOS_PROJECT_DIR 为绝对路径并校验存在。本期 iOS demo 未落地（device-ios 二期），
# 默认 demo-ios/ 不存在时在此 exit 4——脚本仍就位，二期落地后即生效。
resolve_project_dir() {
  case "$IOS_PROJECT_DIR" in
    /*) PROJECT_DIR_ABS="$IOS_PROJECT_DIR" ;;
    *)  PROJECT_DIR_ABS="$HANDEYE_ROOT/$IOS_PROJECT_DIR" ;;
  esac
  if [ ! -d "$PROJECT_DIR_ABS" ]; then
    die "iOS demo 未落地：$PROJECT_DIR_ABS 不存在（挂 device-ios 二期，落地后本脚本即生效）" 4
  fi
}

# 计算 xcodebuild 的工程参数：IOS_WORKSPACE 非空用 -workspace（相对工程目录解析），
# 否则自动探测工程目录下第一个 *.xcodeproj。两者都没有 = 前置缺失 exit 4。
project_args() {
  if [ -n "$IOS_WORKSPACE" ]; then
    local ws
    case "$IOS_WORKSPACE" in
      /*) ws="$IOS_WORKSPACE" ;;
      *)  ws="$PROJECT_DIR_ABS/$IOS_WORKSPACE" ;;
    esac
    [ -d "$ws" ] || die "IOS_WORKSPACE 不存在：$ws（检查 handeye.local.sh 的 IOS_WORKSPACE 配置）" 4
    echo "-workspace $ws"
    return 0
  fi
  local projs count proj
  projs=$(ls -d "$PROJECT_DIR_ABS"/*.xcodeproj 2>/dev/null)
  [ -n "$projs" ] || die "$PROJECT_DIR_ABS 下没有 *.xcodeproj（IOS_WORKSPACE 为空时按 -project 构建，需要 xcodeproj；或配 IOS_WORKSPACE）" 4
  count=$(printf '%s\n' "$projs" | awk 'NF {n++} END {print n+0}')
  proj=$(printf '%s\n' "$projs" | head -1)
  if [ "$count" -gt 1 ]; then
    warn "探测到 $count 个 *.xcodeproj 且未配 IOS_WORKSPACE，使用第一个：$proj——多工程目录请显式配 IOS_WORKSPACE 以免选错"
  fi
  echo "-project $proj"
}

# ---- build / install ----

# destination：有 udid 用 id=<udid>（对准具体真机），否则 generic/platform=iOS（只出包）。
destination() {
  if [ -n "$UDID" ]; then
    echo "id=$UDID"
  else
    echo "generic/platform=iOS"
  fi
}

# $1=模式：build（xcodebuild 构建后定位产物）/ reuse（--skip-build 复用已有产物），失败 exit 9。
# 只负责定位并把路径写入 APP_PATH；契约行 HANDEYE_APP_PATH 由 emit_app_path 在
# post-build 钩子之后输出（对齐 Android 兄弟脚本：钩子失败不应已 emit 契约）。
# 多个候选 .app 时按 mtime 最新优先（对齐 Android 的 ls -t 语义，避免复用到陈旧产物）：
# BSD stat 优先（macOS）、GNU stat 兜底（Linux）；'%m 路径' 只剥第一个空格，路径含空格安全。
locate_app() {
  local newest
  newest=$(find "$DERIVED_DATA" -name "*.app" -path "*Debug*" -exec stat -f '%m %N' {} + 2>/dev/null \
    | sort -rn | head -1)
  if [ -z "$newest" ]; then
    newest=$(find "$DERIVED_DATA" -name "*.app" -path "*Debug*" -exec stat -c '%Y %n' {} + 2>/dev/null \
      | sort -rn | head -1)
  fi
  APP_PATH="${newest#* }"
  if [ -z "$APP_PATH" ]; then
    if [ "$1" = "reuse" ]; then
      printf '错误: --skip-build 但 DerivedData 里没有已有 .app：%s（-name "*.app" -path "*Debug*"）\n' "$DERIVED_DATA" >&2
      printf '  先 build 一次，或去掉 --skip-build。\n' >&2
    else
      printf '错误: build 完成但 DerivedData 里找不到 .app 产物：%s（-name "*.app" -path "*Debug*"）\n' "$DERIVED_DATA" >&2
    fi
    exit 9
  fi
  log "APP: $APP_PATH"
}

emit_app_path() {
  echo "HANDEYE_APP_PATH=$APP_PATH"
}

build_app() {
  # pargs 故意不加引号：需分词成 -workspace/-project 与路径两个参数，与 _common.sh 的 $ADB 同款约定
  local pargs dest
  pargs=$(project_args)
  dest=$(destination)
  log "[build] cd $PROJECT_DIR_ABS && xcodebuild $pargs -scheme $IOS_SCHEME -configuration Debug -destination $dest -derivedDataPath $DERIVED_DATA -allowProvisioningUpdates build"
  if ! ( cd "$PROJECT_DIR_ABS" && xcodebuild $pargs \
      -scheme "$IOS_SCHEME" \
      -configuration Debug \
      -destination "$dest" \
      -derivedDataPath "$DERIVED_DATA" \
      -allowProvisioningUpdates \
      build ); then
    printf '错误: xcodebuild build 失败\n' >&2
    printf '  常见原因：签名/Provisioning 未配置真机 · Provisioning Profile 过期或未含当前真机 udid · 依赖未同步 · scheme 名与工程不符\n' >&2
    printf '  重跑看完整日志: cd %s && xcodebuild %s -scheme %s -configuration Debug -destination %s -derivedDataPath %s -allowProvisioningUpdates build\n' \
      "$PROJECT_DIR_ABS" "$pargs" "$IOS_SCHEME" "$dest" "$DERIVED_DATA" >&2
    exit 9
  fi
  locate_app "build"
}

install_app() {
  log "[install] xcrun devicectl device install app --device $UDID $APP_PATH"
  if ! xcrun devicectl device install app --device "$UDID" "$APP_PATH"; then
    printf '错误: devicectl install 失败\n' >&2
    printf '  常见原因：签名不匹配目标真机 · Provisioning 未含该 udid · 真机未解锁信任本机\n' >&2
    exit 10
  fi
}

# ---- 软前置提示（不阻断、不询问，§5.1） ----
# 探测工程目录的 Podfile：含 :path 本地 pod（KMP/本地 framework 联调态常见形态）打 info；
# 无 Podfile 或没有 :path 依赖打 warn，指路 docs/integration-points.md 的 iOS 接入路线。
print_integration_hint() {
  local podfile="$PROJECT_DIR_ABS/Podfile"
  log "集成态检查（软前置，不阻断）"
  if [ -f "$podfile" ]; then
    # 先滤注释行再判 :path：注释里的 :path 字样（如指引样例）不算数
    if grep -v '^[[:space:]]*#' "$podfile" | grep -q ':path'; then
      log "Podfile 含 :path 本地 pod（源码联调态常见形态），确认 debug 能力已打进包"
    else
      warn "Podfile 未含 :path 本地 pod——若经 KMP/本地 framework 联调，注入样例见 docs/integration-points.md"
    fi
  else
    warn "工程目录无 Podfile——iOS 接入路线（KMP Podfile 注入 / device-ios SPM 二期）见 docs/integration-points.md"
  fi
}

main() {
  parse_args "$@"

  if [ -n "$SKIP_BUILD" ] && [ -n "$SKIP_INSTALL" ]; then
    log "--skip-build --skip-install：无事可做，直接返回"
    exit 0
  fi

  # 配置加载放在 -h / 双 skip 短路之后：打帮助和双 skip 不需要任何配置
  load_config

  # 固定 DerivedData 目录，便于定位/复用产物（走三级配置：环境变量 > local.sh > 默认值；
  # 解析放在 load_config 之后，local.sh 里的 IOS_DERIVED_DATA 才能生效）
  DERIVED_DATA="${IOS_DERIVED_DATA:-$HANDEYE_ROOT/build/derived-data/ios}"

  # xcodebuild / devicectl 属可自动检测的工具链，保持硬检查（§5.1）
  if [ -z "$SKIP_BUILD" ]; then
    require_tools "xcodebuild:装完整 Xcode"
  fi
  if [ -z "$SKIP_INSTALL" ]; then
    if ! command -v devicectl >/dev/null 2>&1 && ! xcrun devicectl --help >/dev/null 2>&1; then
      printf '缺少 devicectl（装 .app 到真机；Xcode 自带 xcrun devicectl，缺则装完整 Xcode）\n' >&2
      exit 4
    fi
  fi

  resolve_project_dir

  # udid：要装包则必须解析到（exit 4）；仅 --skip-install 时解析不到可回退 generic 目的地
  if ! resolve_udid; then
    if [ -z "$SKIP_INSTALL" ]; then
      die "没有目标真机 udid（缺少 -u / HANDEYE_UDID / fixtures .devices.ios_udid / idevice_id 无结果）；iOS 多机时必须显式指定" 4
    fi
    log "未解析到 udid，构建 destination 用 generic/platform=iOS"
  fi

  if [ -z "$SKIP_BUILD" ]; then
    run_hook pre-build
    build_app
    run_hook post-build
    emit_app_path
    print_integration_hint
  elif [ -z "$SKIP_INSTALL" ]; then
    locate_app "reuse"
    emit_app_path
  fi

  if [ -z "$SKIP_INSTALL" ]; then
    run_hook pre-install
    install_app
    run_hook post-install
  fi
}

main "$@"
