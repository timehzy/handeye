#!/usr/bin/env bash
# handeye 脚手架公共层：配置加载 / 依赖检查 / 端口发现 / 健康检查 / 钩子。
# 被各脚本 source；不单独执行。
set -eu

HANDEYE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 三级配置读取：环境变量 > handeye.local.sh > handeye.example.sh 默认值。
# 先 source local 再 source example：example 里的 ": ${VAR:=default}" 只在未设置时赋值，
# 不覆盖 local（或环境变量）已设的值。
load_config() {
  [ -f "$HANDEYE_ROOT/scripts/handeye.local.sh" ] && . "$HANDEYE_ROOT/scripts/handeye.local.sh"
  [ -f "$HANDEYE_ROOT/scripts/handeye.example.sh" ] && . "$HANDEYE_ROOT/scripts/handeye.example.sh"
  # FIXTURES_JSON 归一为绝对路径（example 模板给的是相对路径，[ -f ] 不能依赖调用方 CWD）
  case "$FIXTURES_JSON" in
    /*) ;;
    *)  FIXTURES_JSON="$HANDEYE_ROOT/$FIXTURES_JSON" ;;
  esac
  if [ ! -f "$FIXTURES_JSON" ]; then
    warn "FIXTURES_JSON 不存在：$FIXTURES_JSON，回退到示例配置"
    FIXTURES_JSON="$HANDEYE_ROOT/fixtures/e2e.example.json"
  fi
  require_vars APP_ID HANDEYE_DEEPLINK_SCHEME
}

log()  { printf '==> %s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
# die <msg> [exit_code]：打印错误并以指定退出码（默认 1）结束。
die()  { printf '错误: %s\n' "$*" >&2; exit "${2:-1}"; }

# 依赖检查：逐项打印安装指引，全部缺失项列出后统一退出（exit 4）。
# 用法: require_tools "adb:Android SDK platform-tools" "jq:brew install jq" ...
require_tools() {
  local missing=0 spec tool hint
  for spec in "$@"; do
    tool="${spec%%:*}"; hint="${spec#*:}"
    command -v "$tool" >/dev/null 2>&1 || { printf '缺少 %s（%s）\n' "$tool" "$hint" >&2; missing=1; }
  done
  [ "$missing" -eq 0 ] || exit 4
}

# 必填变量检查：列出每个缺失变量的名字与设置途径（环境变量或 handeye.local.sh），
# 避免 bash 直接抛 unbound variable 让人摸不着头脑。全部缺失项列出后 exit 3。
# 用法: require_vars "APP_ID" "HANDEYE_DEEPLINK_SCHEME"
require_vars() {
  local missing=0 v
  for v in "$@"; do
    [ -n "${!v:-}" ] || { printf '缺少配置 %s（用环境变量或 scripts/handeye.local.sh 设置）\n' "$v" >&2; missing=1; }
  done
  [ "$missing" -eq 0 ] || exit 3
}

# 等 App 进程出现，返回 pid（stdout）。40 次 × 0.5s = 20s 超时返回 1。
# pidof 在罕见的多用户/重复安装场景可能返回多个 pid，只取第一个。
# 调用前需设置 $ADB（"adb" 或 "adb -s <serial>"，本函数内不加引号、故意分词）。
wait_app_pid() {
  local pid="" i
  for i in $(seq 1 40); do
    pid="$($ADB shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')"
    [ -n "$pid" ] && { pid="${pid%% *}"; printf '%s' "$pid"; return 0; }
    sleep 0.5
  done
  return 1
}

# 从 /proc/<pid>/net/tcp{,6} 反查 debug server 端口：只取 loopback LISTEN(0A) 候选，
# 逐个 adb forward 后用 /health 实测，第一个应答者为准。（整网络命名空间可见，必须实测。）
# 成功后那个可用的 adb forward 会刻意保留给调用方继续使用，不在此清理。
# $1=pid，stdout=端口；找不到返回 1。
discover_debug_server_port() {
  local pid="$1" hex dec
  local candidates
  candidates="$($ADB shell "cat /proc/$pid/net/tcp /proc/$pid/net/tcp6 2>/dev/null" \
    | awk '$4=="0A"{split($2,a,":");if(a[1]=="0100007F"||a[1]=="0000000000000000FFFF00000100007F")print a[2]}' \
    | tr -d '\r' | sort -u)"
  while IFS= read -r hex; do
    [ -z "$hex" ] && continue
    case "$hex" in *[!0-9a-fA-F]*) continue ;; esac  # 防御：非法 hex 会让 $((16#...)) 在 set -e 下直接退出
    dec=$((16#$hex)); [ "$dec" -gt 1024 ] || continue
    $ADB forward "tcp:$dec" "tcp:$dec" >/dev/null 2>&1 || continue
    if curl -sf -m 2 "http://127.0.0.1:$dec/health" >/dev/null 2>&1; then printf '%s' "$dec"; return 0; fi
    $ADB forward --remove "tcp:$dec" >/dev/null 2>&1 || true
  done <<EOF
$candidates
EOF
  warn "pid $pid 的 loopback LISTEN 候选都未通过 /health 实测"
  return 1
}

# probe /health 直到就绪（30 次 × 0.5s = 15s）：就绪标准 = HTTP 200 且 .ok=true
probe_health() { # $1=baseUrl
  local i
  for i in $(seq 1 30); do
    curl -sf -m 2 "$1/health" 2>/dev/null | grep -Eq '"ok": ?true' && return 0
    sleep 0.5
  done
  warn "$1/health 15s 内未就绪，最后重试一次"
  curl -sf -m 2 "$1/health" 2>/dev/null | grep -Eq '"ok": ?true'
}

# 钩子执行器：依次尝试 $HANDEYE_HOOK_<NAME>（命令字符串）与 scripts/hooks/<name>.sh
# （可执行文件）。两者都不存在 = 静默跳过（钩子可选）。
# 用法: run_hook pre-build
run_hook() {
  local env_name="HANDEYE_HOOK_$(printf '%s' "$1" | tr 'a-z-' 'A-Z_')"
  local cmd="${!env_name:-}"
  if [ -n "$cmd" ]; then eval "$cmd"; return; fi
  local file="$HANDEYE_ROOT/scripts/hooks/$1.sh"
  if [ -x "$file" ]; then "$file"; return; fi
}

# 从脚本文件头部注释提取 usage（awk NR==1 跳 shebang），头部注释即 usage 数据源。
# 假定第 1 行是 shebang（NR==1 无条件跳过）。
usage_from_header() { # $1=脚本路径
  awk 'NR==1{next} /^#/{sub(/^# ?/,"");print;next}{exit}' "$1"
}
