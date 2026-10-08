#!/usr/bin/env bash
# demo 专属入口 = scripts/run.sh 的薄封装（demo 出厂配置已在 handeye.example.sh）。
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec "$ROOT/scripts/run.sh" "$@"
