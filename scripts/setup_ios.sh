#!/usr/bin/env bash
# handeye · iOS debug server 端口隧道（iproxy + /health 探测）
#
# iOS 真机无 /proc 反查端口：device 端 debug server 固定端口约定（默认 27778，
# 见 $IOS_DEVICE_PORT），iproxy 把 host 侧 localPort 转发到该固定端口，再 probe /health。
# 与 setup_android.sh 对齐：输出契约行 + 探测就绪；区别仅在隧道工具与端口来源。
#
# 用法:
#   ./setup_ios.sh [localPort] [-u udid]
#
# 参数:
#   localPort   可选。host 侧监听端口；缺省 = device 端口（$IOS_DEVICE_PORT）。
#   -u udid     可选。指定目标设备 udid；缺省取 $HANDEYE_UDID / fixtures 的
#               .devices.ios_udid / idevice_id -l 第一台（多机时须显式指定，
#               idevice_id 输出排序不可控，取第一台可能取错设备）。
#   -h, --help  打帮助
#
# 前置:
#   1. libimobiledevice 已装：idevice_id / iproxy 在 PATH（brew install libimobiledevice）
#   2. iOS 真机通过 USB 连接（idevice_id 能看到），已解锁并信任本机
#   3. debug 变体 App 已启动，且装配器 HandeyeInstaller.install 已成功（debug server 已监听）
#
# 输出契约（供 run.sh 消费，解析 stdout 里的 KEY=VALUE 行；契约行先于脚本阻塞输出）:
#   HANDEYE_HOST_PORT=<hostPort>
#   HANDEYE_DEVICE_PORT=<devicePort>
#
# 隧道生命周期:
#   隧道由本脚本持有的 iproxy 子进程维持——契约行输出后脚本前台驻留（wait iproxy），
#   杀掉本脚本（Ctrl-C / TERM / 正常退出）即拆隧道并释放 host 端口。需要常驻隧道的
#   调用方保持本脚本运行（如后台启动并从 stdout 读契约行），或自建 iproxy 管理。
#   拆除信号请用 TERM（后台拉起后 kill 的默认信号即 TERM）；非 job control 场景下
#   INT（Ctrl-C 语义）对后台脚本不可靠（SIGINT 会被忽略进入）。
#
# 退出码:
#   0  成功（隧道建立且 /health 就绪）
#   1  前置缺失（iproxy / idevice_id / curl / jq 未装）或 iOS 真机未连接
#   2  参数错误 / host 端口未就绪（iproxy 存活但端口一直不可连接）
#   3  iproxy 隧道失败（建立时起后秒退，host 端口可能被占用；或运行期 iproxy 中途退出致隧道失效）
#   4  /health 未就绪
set -eu

. "$(dirname "$0")/_common.sh"

LOCAL_PORT=""
UDID=""
IPROXY_PID=""
DEVICE_PORT=""

# ---- 参数解析 ----

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -u)
        shift 1
        [ $# -gt 0 ] || { printf '错误: -u 需要 udid 参数\n' >&2; exit 2; }
        UDID="$1"; shift 1 ;;
      -h|--help)  usage_from_header "$0" && exit 0 ;;
      -*)
        printf '错误: 未知参数 %s\n' "$1" >&2; usage_from_header "$0" >&2; exit 2 ;;
      *)
        [ -z "$LOCAL_PORT" ] || { printf '错误: localPort 只能给一个（收到: %s %s）\n' "$LOCAL_PORT" "$1" >&2; exit 2; }
        LOCAL_PORT="$1"; shift 1 ;;
    esac
  done
  if [ -n "$LOCAL_PORT" ]; then
    case "$LOCAL_PORT" in
      *[!0-9]*) die "localPort 必须是数字（收到: $LOCAL_PORT）" 2 ;;
    esac
  fi
}

# ---- 前置检查 ----
# 不用 _common.sh 的 require_tools：它统一 exit 4（前置缺失语义），而本脚本 4 留给
# /health 未就绪；iproxy / 真机类前置缺失按本脚本约定 exit 1，逐项列出缺失与安装指引。
# 只查本脚本真正用到的工具——afcclient / devicectl 不在此列（本脚本纯 iproxy 工作流，
# 多余的检查会把正常环境无故拦在 exit 1）。
require_ios_tools() {
  local missing=0
  command -v idevice_id >/dev/null 2>&1 || { printf '缺少 idevice_id（brew install libimobiledevice）\n' >&2; missing=1; }
  command -v iproxy    >/dev/null 2>&1 || { printf '缺少 iproxy（brew install libimobiledevice）\n' >&2; missing=1; }
  command -v curl      >/dev/null 2>&1 || { printf '缺少 curl（macOS 自带，重装命令行工具: xcode-select --install）\n' >&2; missing=1; }
  [ "$missing" -eq 0 ] || exit 1
}

# ---- udid 解析 ----
# 优先级：-u 参数 > $HANDEYE_UDID > fixtures 的 .devices.ios_udid（jq 读取）>
# idevice_id -l 第一台。仅走到 fixtures 分支才需要 jq（对齐上游的惰性依赖）。
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
  command -v jq >/dev/null 2>&1 || die "未找到 jq，无法读取 \$FIXTURES_JSON 的 .devices.ios_udid（brew install jq；或用 -u / HANDEYE_UDID 显式指定设备以跳过）" 1
  UDID=$(jq -r '.devices.ios_udid // ""' "$FIXTURES_JSON" 2>/dev/null || true)
  if [ -n "$UDID" ]; then
    log "目标设备（fixtures .devices.ios_udid）: $UDID"
    return
  fi
  local devices count
  devices=$(idevice_id -l 2>/dev/null || true)
  count=$(printf '%s\n' "$devices" | awk 'NF {n++} END {print n+0}')
  if [ "$count" -eq 0 ]; then
    die "没有连接的 iOS 真机（idevice_id -l 为空）。确认 USB 连接 + 解锁 + 信任本机" 1
  fi
  if [ "$count" -gt 1 ]; then
    warn "检测到 $count 台设备且未指定（-u / HANDEYE_UDID / fixtures），idevice_id 输出排序不可控，使用第一台——多机场景请显式指定以免取错设备"
  fi
  UDID=$(printf '%s\n' "$devices" | head -1)
  log "目标设备（idevice_id -l 第一台）: $UDID"
}

# ---- 等 host 端口可连接（iproxy 已 listen） ----
# iproxy 存活不代表端口已 bind 可连（冷机/高负载有延迟）；10s（20 × 0.5s）不可连 = 端口未就绪。
# 探测是向 host 端口真实发起 TCP 连接（最多 20 次），连接经 iproxy 到达 device 端 debug
# server——无害：server 把空连接正常关闭即可，不影响随后的 /health 等请求。
wait_port_listening() { # $1=port
  local i
  for i in $(seq 1 20); do
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0
    sleep 0.5
  done
  return 1
}

# ---- 主流程 ----

main() {
  parse_args "$@"

  # 配置加载放在 parse_args 之后：-h 不需要任何配置（与 setup_android.sh 一致）
  load_config
  require_ios_tools

  DEVICE_PORT=$IOS_DEVICE_PORT
  resolve_udid

  local host_port
  host_port="${LOCAL_PORT:-$DEVICE_PORT}"

  # 建隧道：iproxy 常驻后台，stdout/stderr 重定向 /dev/null——否则它继承脚本 stdout，
  # 调用方从后台运行的本脚本 stdout 读契约行时会等 iproxy 释放 stdout 而永远读不到。
  log "iproxy $host_port:$DEVICE_PORT (udid=$UDID)"
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # 仅 udid 非空时加 -u（iproxy 新语法: 「冒号分隔端口对 + -u 指定 udid」）；
  # ${UDID:+-u "$UDID"} 故意不加引号：需分词成 -u 与 udid 两个参数，与 _common.sh 的 $ADB 同款约定
  iproxy "$host_port:$DEVICE_PORT" ${UDID:+-u "$UDID"} >/dev/null 2>&1 &
  IPROXY_PID=$!
  sleep 1
  # 起后秒退 = 隧道建立失败（host 端口被占 / iproxy 版本语法不符）
  if ! kill -0 "$IPROXY_PID" 2>/dev/null; then
    die "iproxy 起后秒退（host 端口 $host_port 可能被占用，换个 localPort；或 iproxy 语法与版本不符）" 3
  fi

  log "等 host 端口 $host_port 可连接"
  if ! wait_port_listening "$host_port"; then
    die "host 端口 $host_port 未就绪（iproxy 存活但 10s 内一直不可连接）" 2
  fi

  log "探测 http://127.0.0.1:$host_port/health"
  if ! probe_health "http://127.0.0.1:$host_port"; then
    die "/health 未就绪（隧道已建但 debug server 无响应；确认 App 已启动且 HandeyeInstaller.install 成功后重试）" 4
  fi

  # 输出契约行（run.sh 按行解析 KEY=VALUE）。必须先于下方 wait 输出：
  # 契约给出后脚本前台驻留持有隧道，调用方读完即可用；杀脚本即拆隧道（见文件头「隧道生命周期」）
  echo "HANDEYE_HOST_PORT=$host_port"
  echo "HANDEYE_DEVICE_PORT=$DEVICE_PORT"
  cat <<EOF

隧道建立完成（脚本驻留持有隧道，Ctrl-C 退出即拆除）
    device 端口: $DEVICE_PORT（固定端口约定）
    host  端口: $host_port

测试命令:
    curl http://127.0.0.1:$host_port/health
    curl 'http://127.0.0.1:$host_port/events?since=0'

拆除隧道:
    杀掉本脚本（Ctrl-C / kill），或退出后 iproxy 已由脚本清理
EOF
  # 前台驻留：脚本退出（含信号）时 EXIT trap 杀掉 iproxy，隧道随之拆除。
  # iproxy 中途死亡（被外部 kill / 自身崩溃）不能静默吞掉——隧道已没了，须明确报错让调用方重建。
  local proxy_rc=0
  wait "$IPROXY_PID" || proxy_rc=$?
  [ "$proxy_rc" -eq 0 ] || die "iproxy 已退出（隧道已拆除），如需重建隧道请重跑本脚本" 3
}

# EXIT trap：清理本次启动的 iproxy 子进程，失败/异常路径不残留占用 host 端口
cleanup() {
  [ -n "$IPROXY_PID" ] && kill "$IPROXY_PID" 2>/dev/null || true
}

main "$@"
