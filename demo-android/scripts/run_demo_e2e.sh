#!/usr/bin/env bash
# 真机跑批 demo e2e：安装 debug 包 → 启动 App → 反查 debug server 端口 → adb forward → 跑 scenario。
#
# 用法：
#   ./demo-android/scripts/run_demo_e2e.sh                     # 跑全部场景
#   ./demo-android/scripts/run_demo_e2e.sh --scenario refresh_shows_latest_feed
#   ./demo-android/scripts/run_demo_e2e.sh --tag smoke
#   ./demo-android/scripts/run_demo_e2e.sh --serial <device> --all
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if [ -z "${ADB:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
        ADB="${ANDROID_HOME}/platform-tools/adb"
    elif command -v adb >/dev/null 2>&1; then
        ADB="adb"
    else
        ADB="$HOME/Library/Android/sdk/platform-tools/adb"
    fi
fi

SERIAL_ARGS=()
RUN_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL_ARGS=("-s" "$2"); shift 2 ;;
        *) RUN_ARGS+=("$1"); shift ;;
    esac
done

APP_ID="dev.handeye.demo"
APK="$ROOT/demo-android/app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "APK 不存在，先跑：./gradlew :demo-android:app:assembleDebug"; exit 1; }

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi

echo "==> 安装 $APK"
"$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" install -r "$APK" >/dev/null

echo "==> 启动 $APP_ID"
"$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" shell am start -n "$APP_ID/.MainActivity" >/dev/null

PID=""
for _ in $(seq 1 20); do
    PID="$("$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" shell pidof "$APP_ID" | tr -d '\r')"
    [ -n "$PID" ] && break
    sleep 0.5
done
[ -n "$PID" ] || { echo "App 进程未启动"; exit 1; }
echo "==> pid=$PID"

# 从 /proc/<pid>/net/tcp{,6} 反查 debug server 端口。
# /proc/<pid>/net 展示的是整个网络命名空间的 socket，不只本进程——所以不猜端口：
# 只取 loopback（127.0.0.1 / ::ffff:127.0.0.1）上的 LISTEN（状态 0A）候选，
# 逐个 adb forward 后用 /health 实测，第一个应答的才是真身。十六进制端口本地转十进制
# （device 的 toybox awk 没有 strtonum）。
PORT=""
for _ in $(seq 1 30); do
    CANDIDATES="$("$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" shell "cat /proc/$PID/net/tcp /proc/$PID/net/tcp6 2>/dev/null" \
        | awk '$4 == "0A" {
            split($2, a, ":");
            if (a[1] == "0100007F" || a[1] == "0000000000000000FFFF00000100007F") print a[2]
        }' | tr -d '\r' | sort -u)"
    while read -r hex; do
        [ -z "$hex" ] && continue
        dec=$((16#$hex))
        [ "$dec" -gt 1024 ] || continue
        "$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" forward "tcp:$dec" "tcp:$dec" >/dev/null 2>&1
        if curl -sf -m 2 "http://127.0.0.1:$dec/health" >/dev/null 2>&1; then
            PORT="$dec"
            break
        fi
        "$ADB" "${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"}" forward --remove "tcp:$dec" >/dev/null 2>&1
    done <<EOF
$CANDIDATES
EOF
    [ -n "$PORT" ] && break
    sleep 0.5
done
[ -n "$PORT" ] || { echo "未找到 debug server 监听端口"; exit 1; }
echo "==> debug server port=$PORT"

BASE_URL="http://127.0.0.1:$PORT"

echo "==> 等 /health 就绪"
for _ in $(seq 1 30); do
    if curl -sf "$BASE_URL/health" >/dev/null 2>&1; then break; fi
    sleep 0.5
done
curl -sf "$BASE_URL/health" >/dev/null || { echo "/health 未就绪"; exit 1; }

# 首启自动刷新在后台跑；等它落定（isLoading=false 且内存 20 条）再进 scenario，
# 否则它的 cacheWrite/dbWrite 可能落进 scenario 的 act 事件窗口，污染计数断言。
echo "==> 等首启自动刷新完成"
for _ in $(seq 1 30); do
    UI_BODY="$(curl -sf "$BASE_URL/source?name=ui" 2>/dev/null || true)"
    MEM_BODY="$(curl -sf "$BASE_URL/source?name=memory" 2>/dev/null || true)"
    if echo "$UI_BODY" | grep -q '"isLoading":false' \
        && echo "$MEM_BODY" | grep -q '"entryCount":20'; then
        break
    fi
    sleep 0.5
done
sleep 1

if [ ${#RUN_ARGS[@]} -eq 0 ]; then
    RUN_ARGS=(--all)
fi

echo "==> 跑 e2e: ${RUN_ARGS[*]} $BASE_URL"
cd "$ROOT"
./gradlew :demo-android:e2e:run --console=plain --args="${RUN_ARGS[*]} $BASE_URL"
