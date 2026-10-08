#!/usr/bin/env bash
# handeye · Android 构建 + 安装（single source of truth，run.sh 与 bootstrap_android.sh 共用）
#
# build / install 逻辑只维护这一份；新鲜度判定避免「改了源码但设备上还是旧 APK」的踩坑。
#
# 用法:
#   ./build_install_android.sh [--skip-build] [--skip-install]
#
# 参数:
#   --skip-build      跳过 gradle 构建（$ANDROID_GRADLE_TASK），复用已有 APK
#   --skip-install    跳过 adb install -r（APK 已在设备上 / 无需安装）
#   -h, --help        打帮助
#
# 环境变量:
#   FORCE_REINSTALL=1   跳过新鲜度判定，强制重装
#
# 输出契约（供 run.sh 消费）:
#   HANDEYE_APK_PATH=<path>   build 成功（或 --skip-build 复用已有 APK）后 emit 产物路径
#   [freshness] ...           新鲜度判定日志（stdout，供 run.sh 透传）
#
# 退出码:
#   0  成功（build+install 完成 / APK 新鲜跳过安装 / --skip-build --skip-install 直接返回）
#   2  参数错误
#   4  前置缺失：依赖工具（adb / curl / jq / gradlew）、无可用设备，或配置缺失
#      （load_config 的 require_vars 校验 APP_ID / HANDEYE_DEEPLINK_SCHEME 失败）
#   5  gradle build 失败或 build 后找不到 APK 产物
#   6  adb install 失败
#   钩子（pre/post build/install）失败时：脚本以钩子自身的退出码终止（run_hook 不吞错）
set -eu

. "$(dirname "$0")/_common.sh"

SKIP_BUILD=""
SKIP_INSTALL=""
SERIAL=""
ADB=""
APK_PATH=""

# ---- 参数解析 ----

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --skip-build)    SKIP_BUILD=1; shift 1 ;;
      --skip-install)  SKIP_INSTALL=1; shift 1 ;;
      -h|--help)       usage_from_header "$0" && exit 0 ;;
      *) printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
    esac
  done
}

# ---- 前置检查与设备选择（resolve_device 为 _common.sh 公共实现） ----

# ---- APK 新鲜度判定 ----

# epoch → 人类可读（BSD date 优先，GNU 兜底）
fmt_ts() {
  date -r "$1" '+%m-%d %H:%M' 2>/dev/null || date -d "@$1" '+%m-%d %H:%M' 2>/dev/null || echo "$1"
}

# 文件 mtime（epoch 秒）。BSD stat 优先（macOS），GNU stat 兜底（Linux）
file_mtime() {
  stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0
}

# 设备上已装 APK 的安装时间（epoch 秒）。取 base.apk 文件 mtime——adb install 落盘时间
# 即 lastUpdateTime，且不受 dumpsys 输出格式/系统语言影响。split APK（App Bundle）场景
# pm path 会输出多行，这里只取第一行 base.apk 的 mtime；当前单 APK install -r 流程无影响。
# 拿不到（未安装/无权限）返回空。
device_apk_install_ts() {
  local apk_path ts
  apk_path=$($ADB shell pm path "$APP_ID" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')
  [ -z "$apk_path" ] && return 0
  ts=$($ADB shell stat -c %Y "$apk_path" 2>/dev/null | tr -d '\r' | head -1)
  case "$ts" in
    ''|*[!0-9]*) ;;                 # 非数字 = 拿不到，返回空（按陈旧处理）
    *) echo "$ts" ;;
  esac
}

# 本地影响 APK 的源码最新变更时间（epoch 秒）。对 $SOURCE_PATHS_ANDROID 逐个 watched path
# 取两维 max：
#   1) git 最后触达该 path 的 commit 时间（已提交改动；pull 进来的新 commit 也算）
#   2) working tree 未提交/未跟踪文件的 mtime（改了没 commit 是最常见踩坑形态）
# watched path 均相对 $HANDEYE_ROOT（handeye 单仓，不走双仓逻辑）。
newest_source_ts() {
  local newest=0 t m f path
  for path in $SOURCE_PATHS_ANDROID; do
    t=$(git -C "$HANDEYE_ROOT" log -1 --format=%ct -- "$path" 2>/dev/null || echo 0)
    case "$t" in
      ''|*[!0-9]*) ;;
      *) [ "$t" -gt "$newest" ] && newest=$t ;;
    esac
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      f="${f##* -> }"   # rename（R old -> new）取 -> 后的新路径
      m=$(file_mtime "$HANDEYE_ROOT/$f")
      case "$m" in
        ''|*[!0-9]*) ;;
        *) [ "$m" -gt "$newest" ] && newest=$m ;;
      esac
    done < <(git -C "$HANDEYE_ROOT" status --porcelain -- "$path" 2>/dev/null | sed -E 's/^.. //; s/^"//; s/"$//')
  done
  echo "$newest"
}

# APK 是否需要重装。判定日志直接 echo 到 stdout（[freshness] 前缀，供 run.sh 透传）；
# 语义走 return code：0 = 需重装，1 = 新鲜可跳过。
apk_needs_reinstall() {
  if [ "${FORCE_REINSTALL:-0}" = "1" ]; then
    echo "[freshness] FORCE_REINSTALL=1，强制重装"
    return 0
  fi
  local device_ts src_ts
  device_ts=$(device_apk_install_ts)
  if [ -z "$device_ts" ]; then
    echo "[freshness] 拿不到设备 APK 安装时间（未安装？），按陈旧处理（保守方向）"
    return 0
  fi
  src_ts=$(newest_source_ts)
  if [ "$src_ts" -gt "$device_ts" ]; then
    echo "[freshness] 设备 APK（$(fmt_ts "$device_ts")）早于最近源码变更（$(fmt_ts "$src_ts")），需重装"
    return 0
  fi
  echo "[freshness] APK 新鲜（安装于 $(fmt_ts "$device_ts") ≥ 源码 $(fmt_ts "$src_ts")），跳过安装"
  return 1
}

# ---- build / install ----

build_apk() {
  log "[build] cd $HANDEYE_ROOT && ./gradlew $ANDROID_GRADLE_TASK"
  if ! ( cd "$HANDEYE_ROOT" && ./gradlew "$ANDROID_GRADLE_TASK" --console=plain ); then
    printf '错误: gradle build 失败（task: %s）\n' "$ANDROID_GRADLE_TASK" >&2
    printf '  常见原因：JDK 版本不兼容 · 依赖未同步 · Kotlin 编译错\n' >&2
    printf '  重跑并看完整日志: cd %s && ./gradlew %s --stacktrace\n' "$HANDEYE_ROOT" "$ANDROID_GRADLE_TASK" >&2
    exit 5
  fi
}

# 按 $ANDROID_APK_GLOB 取最新 APK（ls 的 glob 故意不加引号，依赖词法展开）。
# $1=模式：build（gradle 构建后定位产物）/ reuse（--skip-build 复用已有 APK），失败 exit 5。
# 副作用：成功后向 stdout emit 契约行 HANDEYE_APK_PATH=<path>（供 run.sh 消费）。
locate_apk() {
  APK_PATH=$(ls -t $HANDEYE_ROOT/$ANDROID_APK_GLOB 2>/dev/null | head -1)
  if [ -z "$APK_PATH" ]; then
    if [ "$1" = "reuse" ]; then
      printf '错误: --skip-build 但没找到已有 APK：%s/%s\n' "$HANDEYE_ROOT" "$ANDROID_APK_GLOB" >&2
      printf '  先 build 一次，或去掉 --skip-build。\n' >&2
    else
      printf '错误: build 完成但按 %s/%s 找不到 APK 产物\n' "$HANDEYE_ROOT" "$ANDROID_APK_GLOB" >&2
    fi
    exit 5
  fi
  log "APK: $APK_PATH"
  echo "HANDEYE_APK_PATH=$APK_PATH"
}

install_apk() {
  log "[install] $ADB install -r $APK_PATH"
  if ! $ADB install -r "$APK_PATH"; then
    printf '错误: adb install 失败\n' >&2
    printf '  常见原因：\n' >&2
    printf '    - 签名冲突（先 %s uninstall %s 再重试）\n' "$ADB" "$APP_ID" >&2
    printf '    - 设备存储不足\n' >&2
    printf '    - 设备锁屏 / 未信任本机 debug 证书\n' >&2
    exit 6
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

  # curl / jq 本脚本不直接调用，是契约要求的公共工具集（同族脚本共用一份前置检查），勿删
  require_tools "adb:Android SDK platform-tools" "curl:macOS 自带" "jq:brew install jq"
  if [ -z "$SKIP_BUILD" ] && [ ! -x "$HANDEYE_ROOT/gradlew" ]; then
    die "gradlew 不可执行：$HANDEYE_ROOT/gradlew（chmod +x gradlew）" 4
  fi
  if [ -z "$SKIP_INSTALL" ]; then
    resolve_device ""
  fi

  if [ -z "$SKIP_BUILD" ]; then
    run_hook pre-build
    build_apk
    run_hook post-build
    locate_apk "build"
  elif [ -z "$SKIP_INSTALL" ]; then
    locate_apk "reuse"
  fi

  if [ -z "$SKIP_INSTALL" ]; then
    if apk_needs_reinstall; then
      run_hook pre-install
      install_apk
      run_hook post-install
    fi
  fi
}

main "$@"
