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
| iOS 原生 | `device-ios` | Swift Package（二期） | 🔜 接口草案 + 启动契约已冻结（`Handeye.swift` + 协议映射 + bootstrap 契约文档） |
| 场景编排（host） | `orchestrator` | JVM 库 + CLI：三端 scenario 共用 | ✅ 0.1.0 |
| runner 脚手架 | `scripts/` | 双端 build/setup/bootstrap + 素材兜底 + 批跑总控 + 集成诊断 | ✅ 0.1.1 |

## 项目结构

```
handeye/
├── protocol/            # 语言无关契约：spec.md + golden 样本（三端共同的线标准）
├── orchestrator/        # 【JVM】场景编排器 + DSL + MockDebugServer testFixtures
├── device-kmp/          # 【KMP】EventRecorder / CommandRegistry / StateProvider / 7 端点
├── device-android/      # 【Android 原生】AAR 入口，转发 device-kmp android 变体
├── device-ios/          # 【iOS 原生·二期】Swift 接口签名草案 + 协议映射文档
├── demo-android/        # 信息流 demo App + 6 个 e2e scenario + 薄封装跑批入口
├── conformance/         # 协议一致性：schema 校验 + golden 对拍 CLI
├── fixtures/            # e2e fixtures 模板：设备号 + 素材反查 catalog（含样例 mp4）
└── scripts/             # runner 侧脚手架：build / setup / bootstrap / 素材兜底 / 批跑总控 / 集成诊断
```

## Quickstart：跑 demo（Android 真机）

```bash
# 1. 构建并安装 demo App，建 adb forward，跑全部 6 个 scenario
./scripts/run.sh --all

# 1b. iOS 真机同理（需 device-ios 二期实现落地后可用）
./scripts/run.sh --ios --all

# 2. 或手动：装包后单跑一个场景
./gradlew :demo-android:e2e:run --args="refresh_shows_latest_feed http://localhost:<port>"
```

> 接入者：从 `scripts/handeye.example.sh` 复制一份 `scripts/handeye.local.sh` 开始
> （已 gitignore），按你的工程改默认值即可；demo 的出厂配置就是这份 example。
> fixtures 模板同理：`fixtures/e2e.example.json` → `fixtures/e2e.local.json`。

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

runner 侧脚手架（构建 / 装包 / 隧道 / 进态注入）按你的工程适配：
复制 `scripts/handeye.example.sh` 为 `scripts/handeye.local.sh` 并改默认值
（该文件已 gitignore，可放心放本机路径与设备号），然后跑
`./scripts/run.sh --check-integration` 诊断集成态。双端逐项接入点
（依赖 / 装配 / 进态 / 构建 / iOS 三条路线 / 生命周期钩子）见
[docs/integration-points.md](docs/integration-points.md)。

## 协议 conformance

```bash
./gradlew :conformance:run --args="validate <events.jsonl>"        # 结构校验
./gradlew :conformance:run --args="diff <actual.jsonl> <golden>"   # golden 对拍（忽略 t 与 seq）
```

## 协议与文档

- 协议契约：`protocol/spec.md` + golden 样本
- 接入点清单（双端逐项 + iOS Podfile 样例）：`docs/integration-points.md`
- scenario 写作手册：`docs/how-to-write.md`
- 真机排障（七坑索引）：`docs/troubleshooting.md`
- fixtures 模板说明：`fixtures/README.md`
- iOS 启动契约：`device-ios/docs/bootstrap-contract.md`
- 设计文档：`docs/superpowers/specs/2026-09-28-handeye-open-source-design.md`
- 实施计划：`docs/superpowers/plans/2026-09-28-handeye-m1-m5.md`、
  `docs/superpowers/plans/2026-09-29-handeye-n1-n4.md`（runner 脚手架 N1–N4）

## License

MIT（见 LICENSE）
