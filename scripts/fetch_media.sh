#!/usr/bin/env bash
# handeye · 单素材两级兜底：设备已有 → 跳过；没有 → adb push host 文件到 files 根 + 相对段。
#
# 用于 fixtures 里 media 条目的宿主兜底（Android）。push 目标 = files 根 + media-path 相对段，
# 与 bootstrap 脚本的拼根约定一致；iOS 侧 push 由 bootstrap_ios.sh 走 afcclient 另走，不经本脚本。
#
# 用法:
#   ./fetch_media.sh --media-path <沙盒相对段> --media-host <host 绝对路径> [-s serial]
#
# 参数:
#   --media-path <相对段>   设备沙盒内相对段（如 e2e_media/xxx.mp4），push 目标 = files 根 + 相对段
#   --media-host <绝对路径> host 本地素材文件绝对路径（push 源），不存在 exit 1
#   -s serial               指定目标设备序列号（多机场景）；缺省 $ANDROID_SERIAL 或 adb devices 第一台
#   -h, --help              打帮助
#
# 输出契约（stdout 最后一行 KEY=VALUE，预留）:
#   HANDEYE_MEDIA_DEVICE_PATH=<相对段>
#   预留契约：当前 bootstrap 组用自身已知相对段拼装 work_path，不解析本行；供未来独立调用方使用
#
# 退出码:
#   0  成功（含设备已有跳过）
#   1  相对段非法 / host 素材文件不存在
#   2  参数错（未知参数 / 缺值 / 缺 --media-path / --media-host）
#   4  前置缺失（adb 未装）/ 设备建目录或 adb push 失败
set -eu

. "$(dirname "$0")/_common.sh"

MEDIA_PATH=""   # --media-path，沙盒内相对段（如 e2e_media/PRO_VID_xxx.mp4）
MEDIA_HOST=""   # --media-host，host 本地素材绝对路径（push 源）
SERIAL=""       # -s 显式设备序列号

# 相对段会拼进 files 根与 deeplink 的 work_path query，独立调用时挡住逃逸/特殊字符
# （校验本体收拢于 _common.sh 的 is_valid_relative_path）。

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --media-path)
        [ -n "${2:-}" ] || die "参数 --media-path 缺少值" 2
        MEDIA_PATH="$2"; shift 2 ;;
      --media-host)
        [ -n "${2:-}" ] || die "参数 --media-host 缺少值" 2
        MEDIA_HOST="$2"; shift 2 ;;
      -s)
        [ -n "${2:-}" ] || die "参数 -s 缺少设备序列号" 2
        SERIAL="$2"; shift 2 ;;
      -h|--help) usage_from_header "$0" && exit 0 ;;
      *) printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
    esac
  done

  [ -n "$MEDIA_PATH" ] || die "缺少 --media-path <沙盒相对段>" 2
  [ -n "$MEDIA_HOST" ] || die "缺少 --media-host <host 绝对路径>" 2
  if ! is_valid_relative_path "$MEDIA_PATH"; then
    printf '错误: --media-path 需为非空相对段，分段非空且不含 ..，仅限字母数字点下划线连字符\n' >&2
    printf "  实际: '%s'\n" "$MEDIA_PATH" >&2
    exit 1
  fi
}

main() {
  parse_args "$@"
  # 配置加载放在 parse_args 之后：-h 不需要任何配置（与 bootstrap_android.sh 一致）
  load_config
  require_tools "adb:Android SDK platform-tools"
  resolve_device "$SERIAL"

  local device_abs="/storage/emulated/0/Android/data/$APP_ID/files/$MEDIA_PATH"

  # 第一级兜底：设备上已有该文件 → 跳过 push
  if $ADB shell test -f "$device_abs" 2>/dev/null; then
    log "设备已有素材，跳过 push: $device_abs"
  else
    # 占位符早警：fixtures 模板（e2e.example.json）的 host_path 是 ABSOLUTE/PATH/TO 形态，
    # 能过反查的形态校验，但要到这里才以「host 素材不存在」晚败。提前 warn 指路，
    # 退出码契约不变（仍走下方 die exit 1）。
    case "$MEDIA_HOST" in
      *ABSOLUTE/PATH/TO*)
        warn "host 路径仍是 fixtures 模板占位符——请复制 fixtures/e2e.example.json 为 fixtures/e2e.local.json 并改写 host_path" ;;
    esac
    # 第二级兜底：从 host 本地 push（源文件不存在 = 无可拉取源，参数/路径错 exit 1）
    [ -f "$MEDIA_HOST" ] || die "host 素材不存在: $MEDIA_HOST" 1
    log "push 素材: $MEDIA_HOST -> $device_abs"
    $ADB shell mkdir -p "$(dirname "$device_abs")" || die "设备建目录失败: $(dirname "$device_abs")" 4
    $ADB push "$MEDIA_HOST" "$device_abs" || die "adb push 失败（真机未连？）" 4
  fi

  # 契约行（预留）：固定为 stdout 最后一行。当前 bootstrap 组用自身已知相对段拼装
  # work_path，不解析本行；供未来独立调用方使用
  echo "HANDEYE_MEDIA_DEVICE_PATH=$MEDIA_PATH"
}

main "$@"
