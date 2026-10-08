# demo-android：信息流 demo + e2e scenario 范例

**你是谁**：handeye 的开源示例——一个不绑定任何业务领域的常见 App
（用户操作 → 网络请求 → 数据缓存 → UI 更新），以及覆盖这条链的 6 个 scenario。

**结构**：`app/`（demo App，handeye L0 接线模板）、`e2e/`（JVM scenario 模块）、
`scripts/run_demo_e2e.sh`（`scripts/run.sh` 的薄封装，透传全部参数）。

**跑起来**：跑全部场景在仓库根执行——见根 README Quickstart，或 `../scripts/run.sh --all`
（真机 6/6 PASS 为准入）。
