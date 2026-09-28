# handeye 开源化设计（首期：Kotlin 核心 + 编排器 + Android demo）

- 日期：2026-09-28
- 状态：设计已确认，待转实施计划
- 上游来源：Insta360 内部 KMP MVI E2E 测试体系（`insedit/e2e` + `editsdk` testhook/debugserver），经「泛化平移」路线开源
- 仓库：`github.com:timehzy/handeye`（MIT），开源版本为唯一事实源

## 1. 定位与目标

handeye 是移动端业务链 E2E 验证框架：**在真机上通过设备内嵌的 HTTP debug server 反注入语义命令，订阅 App 原生可观察面记录因果事件流，从多个命名快照源拉取状态，做跨层断言**。区别于 UI 自动化（不碰手势、不截图、零 token 确定性执行）与单测（不断 Fake 依赖，断真实环境跨层协作）。

- **首期（本 spec）**：Kotlin 设备端核心（KMP 工程可用 + Android 原生 AAR）+ JVM 编排器 + 场景 DSL + Android demo + 协议 conformance 套件。
- **二期（仅冻结接口，不实现）**：`device-ios` 纯 Swift 实现；Agent 驱动走 skill（SKILL.md + 薄脚本），不做 MCP server。
- **不绑定任何业务领域**：剪辑/播放只是我们内部的一个 L0 适配实例，框架核心不认识这些概念。

## 2. 分层架构

```
handeye/
├── protocol/               # 语言无关契约：spec.md + golden 样本（三端共同的线标准）
├── orchestrator/           # 【JVM】场景编排器 + DSL：三端 scenario 共用这一套驱动
├── device-kmp/             # 【KMP 版】KMP 工程依赖（klib 产物）
│   ├── commonMain/         #   EventRecorder / Registry×2 / 端点路由（HTTP 引擎为注入接口）
│   ├── androidDebug/       #   Ktor CIO actual（debug 变体专用）
│   ├── androidRelease/     #   no-op stub（release 零开销）
│   └── iosMain/            #   注入式转发 actual（KMP 工程走 Kotlin/Native framework 接入 iOS）
├── device-android/         # 【Android 原生版】Android 工程依赖（AAR 产物）
│                           #   与 device-kmp 同一份 Kotlin 源码，Gradle 以 AAR 形态重发布，
│                           #   外加 Android 集成辅助（Installer 一行装配、端口发现）
├── device-ios/             # 【iOS 原生版】二期：Swift Package（纯 Swift 实现 L1/L2）
│                           #   本期只放：协议映射文档 + Swift 接口签名草案（Package 骨架）
├── demo-android/           # demo：信息流 App（用户操作→网络→缓存→UI 链）+ 其 scenario 范例
└── conformance/            # 协议一致性套件：同一批 scenario 与 golden 对拍
```

关键决策：

- 包名统一 `dev.handeye.*`（不绑个人/公司域名）。
- `device-android` 不是第二套实现——AAR 内就是 `device-kmp` 同一份源码；真正的第二实现只有二期的 `device-ios`（Swift）。目录并列是为接入者一眼找到入口。
- 每个模块自带 README：一句话「你是谁、给谁用、怎么依赖」。首页 README 放三端接入表。
- `editsdk` 侧迁移不进本 spec：首期 handeye 自立，内部迁移等 handeye 发版后另立 spec。

## 3. 协议契约（protocol/spec.md）

### 3.1 事件 schema（events.jsonl，每行一条）

```json
{"seq": 12, "t": 340, "kind": "networkResponse", "name": "GET /feed", "payload": {...}}
```

- `seq` 进程内单调递增；`t` 单调时钟毫秒（非 epoch，系统校时不造成倒流）。
- `kind` / `name` 为任意字符串（领域自由）；协议只约束信封，不约束内容。

### 3.2 端点表（全部只绑 127.0.0.1）

| 端点 | 方法 | 语义 |
|---|---|---|
| `/health` | GET | 探活 + 订阅就绪 barrier（`{ok, hookInstalled, pid, logFilePath}`） |
| `/cmd` | POST | 反注入命令 `{key, args}` → `{accepted: true}`，仅 ack 入队，不同步返回快照 |
| `/source` | GET | `?name=ui` 拉命名快照源；name 由接入方注册；源不可用时仍返 200 + null |
| `/events` | GET | `?afterSeq=&kinds=` 增量拉事件 |
| `/reset` | POST | `?mode=events` 清事件 |
| `/wait-for` | POST | 轮询等 predicate 满足或超时（Agent / 调试友好） |
| `/snapshot` | POST | 手动 dump 全源现场 |

相对内部现状的三个变化：`/intent`→`/cmd`（去领域名）；三个专用快照端点→通用 `/source?name=`；`/cmd` 去掉同步 stateSnapshot 返回。

### 3.3 端口与隧道

- 端口由平台分配空闲临时端口；Android 侧 `adb forward` 从 `/proc/$PID/net/tcp6` 反查进程真实 LISTEN 端口；iOS（二期）固定端口或约定文件。
- 只绑 `127.0.0.1` 为协议级安全约束，三端共同遵守。

## 4. L1 设备端核心 API（device-kmp/commonMain，领域无关）

```kotlin
// 事件记录：任意 Flow 或回调 → events.jsonl
class EventRecorder(logFilePath: String) {
    fun <T> registerFlow(kind: String, nameKey: String, flow: Flow<T>)
    fun record(kind: String, name: String, payload: JsonObject? = null)
    fun flush()      // 写盘可见性 barrier（/events 读取前调用）
    fun reset()      // 清文件，seq 不回退
}

// 命名快照源：/source?name=x 的提供方
interface StateProvider {
    val name: String
    fun snapshot(): JsonElement?
}

// 命令注册表：/cmd 的 key → 处理器
class CommandRegistry {
    fun register(key: String, handler: (JsonObject) -> Unit)
    val identity: Any   // 供 Installer 判重装（registry 内容变化即重装）
}

// HTTP 引擎：平台 actual / 外部注入
interface HandeyeHttpServer {
    fun start(port: Int)
    fun stop()
    fun registerHandler(path: String, method: String, handler: (Request) -> Response)
}
// androidDebug: Ktor CIO 实现；androidRelease: no-op；
// iosMain: 未注入时抛 IllegalStateException；device-ios（Swift 二期）自带内嵌实现

// 装配入口：壳工程一行调用
object HandeyeInstaller {
    fun install(
        filesDir: String,
        recorder: EventRecorder,
        providers: List<StateProvider>,
        commands: CommandRegistry,
        server: HandeyeHttpServer? = null,   // 未注入时用平台默认
        enabled: Boolean? = null,            // null 时读系统属性 handeye.enabled
    ): InstallResult   // Installed(port, logFilePath, installId) / Skipped / Failed
}
```

关键语义（继承自内部已验证实现）：

- **懒绑定**：`/health` 首次触发时才建立 Flow 订阅，带 `awaitSubscriptionsReady` barrier（replay=0 的流不丢首个 scenario 的事件）。
- **写盘并发模型**：订阅回调只构造 JSON 入无界队列，单 writer 协程顺序写盘；seq 分配与入队同锁。
- **幂等重装**：宿主重建 / registry 身份变化时自动 uninstall + 重装，`installId` 为 owner 凭证。
- **零开销**：release 变体恒 Skipped，不建 socket、不订阅、不建 recorder。
- **装配失败 rollback**：不留半启的 server，返 `InstallResult.Failed`。
- **端点内部异常降级**：一律 200 + 错误字段，不炸 App——测试设施不是业务路径。
- **写盘失败**：丢弃该条事件并记日志，不阻塞业务流。

相对内部现状的改造：`bind(host)` 拉取式 → 注册式（宿主自行 register 流）；`KMPHostViewModel` 类型依赖 → 零类型依赖；`recordIntent` 内置 kind → 接入方在 command handler 里自行 `recorder.record("intent", ...)`。

## 5. 编排器（orchestrator/，平移自 insedit/e2e）

### 5.1 平移清单（文件级 1:1，改包名 + 去内部依赖）

| 内部文件 | 处置 |
|---|---|
| `Dispatcher` / `HttpDispatcher` | 平移，`/intent` → `/cmd` |
| `FactSource` / `HttpFactSource` | 平移，路径参数化已就绪，改 `/source?name=` |
| `EventsFetcher` / `HttpEventsFetcher` / `EventsTailer` | 平移，不动 |
| `E2eContext` / `E2eDefaults` / `DiagnosticContext` | 平移，`healthCheck`/`eventsReset` 签名不变 |
| `HostVmTest`（五阶段）/ `ScenarioScope` / `ActScope` | 平移，阶段名不变 |
| `ExpectFactScope` / `ExpectDependencyScope` / soft assertion | 平移，不动 |
| `ScenarioRunner` / `ScenarioMeta` / `ScenarioRegistry` / CLI | 平移 |
| `ArtifactDumper` / `SnapshotDiff` / `RunRecord` | 平移 |
| `OrchestratorHttpClient` | 平移（自研 HTTP client，无 Ktor 依赖） |
| 20 个单测 + `MockDebugServer` testFixtures | 平移，断言端点新名 |

### 5.2 DSL 变化点（仅三处小改）

1. `hostVmTest` → `handeyeTest`（去 VM 字眼）。
2. `givenState` 块归位动作语义不变。
3. baseline 相对断言机制原样保留（anti-flaky 核心）。

`expectSource` / `consistency` / `expectEvent` / `expectEventCount` / `expectNoEvent` / `expectNoMatchingEvent` 全部不改名。

### 5.3 DSL 示例（demo 中的实际写法）

```kotlin
handeyeTest("refresh_shows_latest_feed") { ctx ->
    act { dispatch("Feed.Refresh") }
    expectFact { baseline ->
        ui<FeedUiFact>    { copy(listSize = baseline.ui.listSize!! + 20) }
        memory<CacheFact> { copy(entryCount = baseline.memory.entryCount!! + 20) }
        persist<DbFact>   { copy(rowCount = baseline.persist.rowCount!! + 20) }
        consistency("ui == memory == persist") { a, b, c -> a.items == b.items && b.items == c.items }
    }
    expectDependencies {
        expectEvent("networkRequest", "GET /feed?page=1")
        expectEventCount("dbWrite", "feed", 20)
        expectNoEvent("networkResponse", name = "GET /feed", matcher = { it.status >= 400 })
    }
}
```

### 5.4 明确不做（YAGNI）

scenario JSON 跨语言化、in-memory Fake 层、可视化 serve 模块、JUnit runner 集成——内部 roadmap 中「未启动」的项，首期不背。

## 6. demo-android（信息流业务链）

### 6.1 App 形态

极简单页信息流：列表 + 下拉刷新 + 点赞。ViewModel + Repository（内存 LruCache）+ Room——刻意用最普通的 Android 架构，让开源用户看到的是自己的影子。

被验证的因果链：

```
用户操作   Feed.Refresh 命令
  ↓
网络请求   GET /feed?page=1（拦截器短路，预制响应，无 socket）
  ↓
内存缓存   LruCache 写入 20 条
  ↓
持久化    Room 插入 20 行
  ↓
UI 更新   loading → success，列表 +20
```

### 6.2 网络层方案：拦截器短路（不起真实 server）

demo 挂一个 OkHttp Interceptor 直接返回预制响应。真实被执行的部分（OkHttp 调用栈、JSON 反序列化、线程切换、主线程 UI 更新）正是 E2E 要验的「真实环境跨层协作」；被消除的部分（socket、端口、网络时序）是 flaky 源。拦截器也是真实工程里 handeye 最典型的接入姿势，demo 顺带教学。

```kotlin
class HandeyeInterceptor(private val recorder: EventRecorder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        recorder.record("networkRequest", req.method + " " + req.url.encodedPath)
        val response = cannedResponseFor(req)
        recorder.record("networkResponse", req.method + " " + req.url.encodedPath,
            buildJsonObject { put("status", response.code) })
        return response
    }
}
```

### 6.3 L0 适配模板（给开源用户抄，约 150 行）

```kotlin
HandeyeInstaller.install(
    filesDir = filesDir.absolutePath,
    recorder = EventRecorder(path).apply {
        registerFlow("state",           "javaClass", viewModel.uiState)
        registerFlow("networkRequest",  "url",       httpInterceptor.requests)
        registerFlow("networkResponse", "url",       httpInterceptor.responses)
        registerFlow("cacheWrite",      "key",       repo.cacheWrites)
    },
    providers = listOf(
        stateProvider("ui")      { viewModel.uiState.value.toJson() },
        stateProvider("memory")  { repo.cache.snapshot().toJson() },
        stateProvider("persist") { db.feedDao().dumpAll().toJson() },
    ),
    commands = CommandRegistry().apply {
        register("Feed.Refresh")    { viewModel.onRefresh() }
        register("Item.ToggleLike") { args -> viewModel.onToggleLike(args.id) }
    },
)
```

### 6.4 demo scenario 集（6 个，覆盖能力完整谱系）

| scenario | 展示的框架能力 |
|---|---|
| `refresh_shows_latest_feed` | 全链路因果 + 三源一致性 + baseline 相对断言 |
| `refresh_failure_keeps_cache` | `expectNoEvent` / 错误态 UI 断言（500 时缓存不被清） |
| `refresh_is_idempotent` | `expectEventCount`（连点两次只发 1 次请求，验防抖） |
| `toggle_like_persists` | 写路径三源同步 + 事件顺序断言（dbWrite 先于 state(success)） |
| `like_survives_recreate` | persist 源独特价值：重建后数据仍在 |
| `cold_start_from_cache` | givenState 归位 + 三源 baseline 语义 |

确定性保障：拦截器预制响应零外部依赖；每个 scenario 前 `baselineReset` 清 DB/缓存。

## 7. conformance 套件与测试策略

### 7.1 conformance

- `protocol/golden/` 存黄金样本：固定「命令 + 事件流」输入，每种 kind 至少一条，含完整信封 JSON。
- `conformance/` 跑两类检查：① schema 校验（seq 单调、t 单调非负、kind/name 非空）；② golden 对拍（demo-android 为参考实现，事件序列剔除 `t` 后与 golden 一致）。
- 二期 `device-ios`（Swift）的验收标准 = 跑过同一 conformance 套件。

### 7.2 各层测试

| 层 | 验证手段 |
|---|---|
| orchestrator | 平移的 20 个单测 + MockDebugServer testFixtures（无真机全绿） |
| device-core（JVM 语义） | androidUnitTest：写盘并发、seq 单调、懒绑定 barrier、幂等重装 |
| device-core（HTTP 面） | 真机 instrumentation 冒烟：起 server → 逐端点 curl → 关 server，进 CI nightly |
| demo-android | 6 个 scenario 即 demo 测试，兼 golden 生产器 |

## 8. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 协议 | `protocol/spec.md` + golden 样本 + schema 定义 | 内部评审通过 |
| M2 设备端核心 | `device-kmp` 泛化改造（注册式 API + 通用端点 + Android actuals） | 单测 + 真机冒烟 |
| M3 编排器 | `orchestrator/` 平移（改包名/端点名，去 KBA 协程 fork） | 平移单测全绿 |
| M4 demo | `demo-android` 信息流 App + 6 scenario + 拦截器 | 全绿 + 产出 golden |
| M5 收尾 | README 三端接入表、LICENSE 核对、CI、0.1.0 tag | GitHub 公开可跑 |

## 9. 范围外（本期明确不做）

- `device-ios` Swift 实现（接口签名已在本 spec 冻结，二期实现）。
- 内部 `editsdk` 迁移到 handeye 依赖（另立 spec）。
- Maven Central 发布（先用 GitHub Packages / jitpack）。
- MCP server / Agent loop（二期用 skill 承接）。
- scenario JSON 跨语言化、in-memory Fake 层、可视化 serve、JUnit runner。
