# demo-android：信息流 demo + e2e scenario 范例

**你是谁**：handeye 的开源示例——一个不绑定任何业务领域的常见 App
（用户操作 → 网络请求 → 数据缓存 → UI 更新），以及覆盖这条链的 6 个 scenario。

**结构**：`app/`（demo App，handeye L0 接线模板）、`e2e/`（JVM scenario 模块）、
`scripts/run_demo_e2e.sh`（真机跑批：装包→端口反查→adb forward→`--all`）。

**跑起来**：`./demo-android/scripts/run_demo_e2e.sh`（真机 6/6 PASS 为准入）。
