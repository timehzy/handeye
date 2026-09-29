# handeye

移动端业务链 E2E 验证框架：在真机上通过设备内嵌的 HTTP debug server 反注入语义命令，
订阅 App 原生可观察面记录因果事件流，从多个命名快照源拉取状态，做跨层断言。

不碰手势、不截图、零 token 确定性执行；不断 Fake 依赖，断的是真实环境里
「用户操作 → 网络请求 → 数据缓存 → UI 更新」的完整因果链。

## 为什么是 handeye

| | UI 自动化（Appium/Espresso 截图流） | 单测 | handeye |
|---|---|---|---|
| 验证对象 | 像素与控件位置 | 单类逻辑（Fake 依赖） | 真机跨层因果链 |
| 稳定性 | 布局改动即碎 | 高 | 高（断语义不断 UI） |
| 断言能力 | 看见什么是什么 | 只信自己造的假数据 | 三源快照（UI/内存/持久化）+ 事件序交叉断言 |
| 驱动方式 | 坐标/手势注入 | JVM 内直接调 | 语义命令（`Feed.Refresh`）经内嵌 HTTP server 下发 |
| 成本 | 高（截图/token/维护） | 低 | 低（JSON 事件流，可进 git diff） |

## 三端接入

| 端 | 模块 | 形态 | 状态 |
|---|---|---|---|
| KMP 工程 | `device-kmp` | klib（commonMain + androidDebug Ktor CIO + androidRelease no-op + iosMain 注入转发） | ✅ 0.1.0 |
| Android 原生 | `device-android` | AAR 入口（承载 device-kmp 同一份源码） | ✅ 0.1.0 |
| iOS 原生 | `device-ios` | Swift Package（二期） | 🔜 接口草案已冻结（`Handeye.swift` + 协议映射） |
| 场景编排（host） | `orchestrator` | JVM 库 + CLI：三端 scenario 共用 | ✅ 0.1.0 |

## 项目结构

```
handeye/
├── protocol/            # 语言无关契约：spec.md + golden 样本（三端共同的线标准）
├── orchestrator/        # 【JVM】场景编排器 + DSL + MockDebugServer testFixtures
├── device-kmp/          # 【KMP】EventRecorder / CommandRegistry / StateProvider / 7 端点
├── device-android/      # 【Android 原生】AAR 入口，转发 device-kmp android 变体
├── device-ios/          # 【iOS 原生·二期】Swift 接口签名草案 + 协议映射文档
├── demo-android/        # 信息流 demo App + 6 个 e2e scenario + 真机跑批脚本
└── conformance/         # 协议一致性：schema 校验 + golden 对拍 CLI
```

## Quickstart：跑 demo（Android 真机）

```bash
# 1. 构建并安装 demo App，反查 debug server 端口，跑全部 6 个 scenario
./demo-android/scripts/run_demo_e2e.sh

# 2. 或手动：装包后单跑一个场景
./gradlew :demo-android:e2e:run --args="refresh_shows_latest_feed http://localhost:<port>"
```

6 个场景覆盖：刷新成功三源一致 / 失败保缓存 / 重复刷新 / 点赞持久化 /
重建恢复 / 冷启动零网络走持久化。

## 接入你的 App（三步）

```kotlin
// 1. 装配：Application.onCreate 一行（release 变体 no-op，零开销）
HandeyeInstaller.install(
    filesDir = filesDir.absolutePath,
    recorder = EventRecorder("$filesDir/handeye/events.jsonl"),
    providers = listOf(
        stateProvider("ui") { /* UI 状态快照 JsonObject */ },
        stateProvider("memory") { /* 内存数据快照 */ },
        stateProvider("persist") { /* 持久化数据快照 */ },
    ),
    commands = CommandRegistry().apply {
        register("Feed.Refresh") { /* 触发业务动作 */ }
    },
    enabled = BuildConfig.DEBUG,
)

// 2. 订阅状态流 / 记录因果事件
recorder.registerFlow(kind = "state", nameKey = "name", serialize = { ... }, flow = { uiStateFlow })
recorder.record("networkRequest", "GET /feed", buildJsonObject { put("page", 1) })

// 3. host 侧写 scenario（DSL 三端通用）
handeyeTest(name = "...", ctx = myContext(baseUrl)) {
    act { dispatch("Feed.Refresh") }
    expectFact { /* 三源快照断言 + 一致性检查 */ }
    expectDependencies { /* 事件序列断言 */ }
}
```

## 协议 conformance

```bash
./gradlew :conformance:run --args="validate <events.jsonl>"        # 结构校验
./gradlew :conformance:run --args="diff <actual.jsonl> <golden>"   # golden 对拍（忽略 t 与 seq）
```

## 协议与文档

- 协议契约：`protocol/spec.md` + golden 样本
- 设计文档：`docs/superpowers/specs/2026-09-28-handeye-open-source-design.md`
- 实施计划：`docs/superpowers/plans/2026-09-28-handeye-m1-m5.md`

## License

MIT（见 LICENSE）
