# orchestrator：场景编排器（JVM）

**你是谁**：handeye 的 host 侧——把语义命令发给被测 App，拉三源快照与事件流，做跨层断言。

**给谁用**：写 e2e scenario 的测试工程师（Mac/Linux，不下发 device）。

**怎么依赖**：`implementation(project(":orchestrator"))`；入口 `handeyeTest(name, ctx)` +
`feedContext` 式 context 工厂；CLI `ScenarioRunner --all <baseUrl>`。
`testFixtures` 提供 MockDebugServer，无真机也能全链路验证 scenario。
