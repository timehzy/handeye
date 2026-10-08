#!/usr/bin/env bash
# handeye · Android debug server 端口暴露（反查 device 端口 + adb forward + /health 探测）
#
# 从 App 进程的 /proc/<pid>/net/tcp{,6} 反查 loopback LISTEN 端口，逐一 /health 实测定位
# debug server（跳过 App 内其它 loopback 服务），建 host→device 转发并探测就绪。
# 不走 logcat：vendor 日志限流（如 LOG_FLOWCTRL）会吃掉装配器日志。
#
# 用法:
#   ./setup_android.sh [localPort] [-s serial]
#
# 参数:
#   localPort   可选。host 侧要暴露的端口号；缺省 = 自动反查到的 device 端口。
#               host 侧端口冲突（已被占用）时显式指定别的空闲端口。
#   -s serial   指定目标设备序列号（多机场景）；缺省读 $ANDROID_SERIAL 或 adb devices 第一台。
#   -h, --help  打帮助
#
# 前置:
#   1. adb 在 PATH，目标设备已连接且 USB 调试已授权
#   2. debug 变体 App 已启动，且装配器 HandeyeInstaller.install 已成功（debug server 已监听）
#
# 输出契约（供 run.sh 消费，解析 stdout 里的 KEY=VALUE 行）:
#   HANDEYE_HOST_PORT=<hostPort>
#   HANDEYE_DEVICE_PORT=<devicePort>
#
# 退出码:
#   0  成功（隧道建立且 /health 就绪）
#   2  参数错误 / App 进程未启动
#   3  adb forward 失败（host 端口可能被占用，换个 localPort）
#   4  前置依赖缺失 / debug server 端口反查失败 / /health 未就绪
set -eu

. "$(dirname "$0")/_common.sh"

LOCAL_PORT=""
SERIAL=""
ADB=""
APP_PID=""
DEVICE_PORT=""

# ---- 参数解析 ----

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -s)
        shift 1
        [ $# -gt 0 ] || { printf '错误: -s 需要设备序列号参数\n' >&2; exit 2; }
        SERIAL="$1"; shift 1 ;;
      -h|--help)  usage_from_header "$0" && exit 0 ;;
      -*)
        printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
      *)
        [ -z "$LOCAL_PORT" ] || { printf '错误: localPort 只能给一个（收到: %s %s）\n' "$LOCAL_PORT" "$1" >&2; exit 2; }
        LOCAL_PORT="$1"; shift 1 ;;
    esac
  done
  case "$LOCAL_PORT" in
    ''|*[!0-9]*) [ -z "$LOCAL_PORT" ] || { printf '错误: localPort 必须是数字（收到: %s）\n' "$LOCAL_PORT" >&2; exit 2; } ;;
  esac
}

# ---- 设备选择（与 build_install_android.sh 同款逻辑的内联副本，两脚本各自维护） ----

# 优先级：-s 参数 > $ANDROID_SERIAL > adb devices 第一台。多机且未指定时 warn 并用第一台。
resolve_device() {
  if [ -n "$SERIAL" ]; then
    log "目标设备（-s 参数）: $SERIAL"
  elif [ -n "${ANDROID_SERIAL:-}" ]; then
    SERIAL=$ANDROID_SERIAL
    log "目标设备（ANDROID_SERIAL）: $SERIAL"
  else
    local devices count first
    devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    count=$(printf '%s\n' "$devices" | awk 'NF {n++} END {print n+0}')
    if [ "$count" -eq 0 ]; then
      die "没有可用设备（adb devices 无在线设备，先连机并确认 USB 调试授权）" 4
    fi
    if [ "$count" -gt 1 ]; then
      warn "检测到 $count 台设备且未指定（-s / ANDROID_SERIAL），使用第一台"
    fi
    first=$(printf '%s\n' "$devices" | head -1)
    SERIAL=$first
    log "目标设备（adb devices 第一台）: $SERIAL"
  fi
  # 故意不整体加引号：$ADB 需要在调用点分词成 "adb -s <serial> ..."（与 _common.sh 约定一致）
  ADB="adb ${SERIAL:+-s $SERIAL}"
}

# ---- 主流程 ----

main() {
  parse_args "$@"

  # 配置加载放在 parse_args 之后：-h 不需要任何配置（与 build_install_android.sh 一致）
  load_config
  require_tools "adb:Android SDK platform-tools" "curl:macOS 自带"
  resolve_device

  log "定位 App 进程 $APP_ID"
  if ! APP_PID=$(wait_app_pid); then
    die "找不到 App 进程 $APP_ID（20s 超时）。请先启动 App 到主页面" 2
  fi
  log "App 进程 PID=$APP_PID"

  log "反查 debug server device 端口（/proc/$APP_PID/net/tcp{,6} loopback LISTEN + /health 实测）"
  if ! DEVICE_PORT=$(discover_debug_server_port "$APP_PID"); then
    printf '错误: 反查 debug server 端口失败。排查指引:\n' >&2
    printf '  - App 是否 debug 变体（release 变体无 debug server）\n' >&2
    printf '  - 装配器 HandeyeInstaller.install 是否已成功（需进入触发页面，见接入文档）\n' >&2
    printf '  - 高负载/冷启动场景下 server 可能还在装配，稍候重跑本脚本\n' >&2
    exit 4
  fi
  log "debug server device 端口: $DEVICE_PORT"

  # host 端口缺省 = device 端口。两者相同时 discover 留下的 tcp:<dec>→tcp:<dec> 转发直接复用；
  # 不同时先清掉 host 端口上可能残留的指向其它 device 端口的 stale forward，再建新的。
  local host_port
  host_port="${LOCAL_PORT:-$DEVICE_PORT}"
  if [ "$host_port" != "$DEVICE_PORT" ]; then
    $ADB forward --remove "tcp:$host_port" >/dev/null 2>&1 || true
    log "adb forward tcp:$host_port tcp:$DEVICE_PORT"
    if ! $ADB forward "tcp:$host_port" "tcp:$DEVICE_PORT" >/dev/null; then
      die "adb forward 失败（host 端口 $host_port 可能被占用；换个 localPort 重试）" 3
    fi
  else
    log "adb forward tcp:$host_port tcp:$DEVICE_PORT（反查阶段已建立，复用）"
  fi

  log "探测 http://127.0.0.1:$host_port/health"
  if ! probe_health "http://127.0.0.1:$host_port"; then
    die "/health 未就绪（隧道已建但 debug server 无响应；确认 HandeyeInstaller.install 成功后重试）" 4
  fi

  # 输出契约行（run.sh 按行解析 KEY=VALUE）
  echo "HANDEYE_HOST_PORT=$host_port"
  echo "HANDEYE_DEVICE_PORT=$DEVICE_PORT"
  cat <<EOF

隧道建立完成
    device 端口: $DEVICE_PORT
    host  端口: $host_port

测试命令:
    curl http://127.0.0.1:$host_port/health
    curl 'http://127.0.0.1:$host_port/events?since=0'

移除隧道:
    $ADB forward --remove tcp:$host_port
EOF
}

main "$@"
