---
title: docs/how-to-write.md · 怎么写 scenario（写作向导）
created: 2026-09-29
---

# 怎么写 scenario（写作向导）

本文讲**写/改** scenario：怎么写 scenario、怎么在目标页里补 Fact / step、加什么新 shortcut 该放哪。协议通用 API 与红线在 [protocol/spec.md](../protocol/spec.md) 与 [conformance/README.md](../conformance/README.md)（协议一致性套件）。

> 想**跑**场景（不是写）→ 看 `./scripts/run.sh -h`。

## 一、目标页（page）分组

业务侧代码按目标页分组：一个 page 对应 App 上一个承载场景集合的业务态（demo 是单页应用，只有一个 page）。demo 的 host 侧 orchestrator 代码布局：

```
demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/
├── FeedContext.kt       # E2eContext 工厂 feedContext(baseUrl)：dispatcher / factSources / eventsFetcher / baselineReset
├── FeedFacts.kt         # 各源 data class Fact（字段全 nullable）+ FeedProjection 投影（原始 JSON → typed Fact）
└── scenarios/
    ├── FeedCatalog.kt   # ScenarioMeta 注册表 feedScenarios
    └── Scenarios.kt     # 具体 scenario 顶层 fun + page 私有 shortcut（private fun）
```

同 page 的 scenario 共享 Fact / Projection / shortcut；跨 page 不共享。接入多页应用时，为每个 page 建一个平级包，重复下面的「page 内部布局」结构即可：

```
<page>/
├── <Page>Context.kt        # E2eContext 工厂函数
├── facts/                  # data class XxxFact（nullable 字段）
├── projection/             # 各源 JSON → typed Fact 的纯函数
├── steps/<Domain>Steps.kt  # 业务动作复用（只做 dispatch + 可选 barrier，不带 assertion）
├── <Page>Catalog.kt        # ScenarioMeta 列表
└── scenarios/*.kt          # 具体 scenario 顶层 fun
```

App 侧（device 端）的接线样例见 [demo-android/.../FeedApp.kt](../demo-android/app/src/main/java/dev/handeye/demo/FeedApp.kt)：`HandeyeInstaller.install(...)` 里注册 stateProvider（各源快照）与 CommandRegistry 命令表。

## 二、业务方要做的 3 件事

以「刷新失败不破坏缓存」（`refresh_failure_keeps_cache`）为例。

### 1. 写单测

在业务逻辑层（ViewModel / 解析器等）的既有单测文件加用例，命名习惯以你的工程为准。demo 的样例：[demo-android/.../BootstrapIntentParserTest.kt](../demo-android/app/src/test/java/dev/handeye/demo/BootstrapIntentParserTest.kt)。

### 2. 写 scenario

Scenario 文件是**顶层 fun**，返回 `ScenarioResult`，签名固定 `(baseUrl: String)`。示例取自 [Scenarios.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/scenarios/Scenarios.kt)（demo 真实代码）：

```kotlin
package dev.handeye.demo.e2e.scenarios

import dev.handeye.demo.e2e.FeedMemoryFact
import dev.handeye.demo.e2e.FeedPersistFact
import dev.handeye.demo.e2e.FeedUiFact
import dev.handeye.demo.e2e.feedContext
import dev.handeye.orchestrator.runner.ScenarioResult
import dev.handeye.orchestrator.runner.handeyeTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.payload(): JsonObject? = this["payload"] as? JsonObject

private fun JsonObject.responseStatus(): Int? = payload()?.get("status")?.jsonPrimitive?.intOrNull

/** 场景 2：刷新失败 —— 网络 500 时 UI 报错，已有内存/持久化数据不被破坏。 */
fun runRefreshFailureKeepsCache(baseUrl: String): ScenarioResult = handeyeTest(
    name = "refresh_failure_keeps_cache",
    ctx = feedContext(baseUrl),
    block = {
        givenState {
            dispatch("Feed.Refresh")
            awaitFact { memoryFact(it)?.entryCount == FEED_PAGE_SIZE }
        }
        act {
            dispatch("Feed._FailNext")
            dispatch("Feed.Refresh")
        }
        expectFact {
            expectSource<FeedUiFact>("ui") {
                copy(isLoading = false, listSize = FEED_PAGE_SIZE, error = "HTTP 500")
            }
            expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
            expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
            consistency("失败不破坏已有数据") { facts ->
                memoryFact(facts)?.items == persistFact(facts)?.items
            }
        }
        expectDependencies {
            expectEvent("networkRequest", "GET /feed")
            expectEvent("networkResponse", "GET /feed") { responseStatus() == 500 }
            expectNoEvent("cacheWrite", "feed")
            expectNoEvent("dbWrite", "feed")
        }
    },
)
```

（示例为节选：`uiFact` / `memoryFact` / `persistFact` 等辅助函数与 `FEED_PAGE_SIZE` 常量定义见 [Scenarios.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/scenarios/Scenarios.kt) 源文件。）

要点：

- `ctx = feedContext(baseUrl)`——每个 page 有自己的 context 工厂：组装 `HttpDispatcher`（POST `/cmd`）、各源 `HttpFactSource`（`/source?name=<源>` + 投影函数）、`HttpEventsFetcher`（`/events`），以及批跑状态隔离用的 `baselineReset`。见 [FeedContext.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/FeedContext.kt)。
- `block = { givenState {…} act {…} expectFact {…} expectDependencies {…} }`——scope DSL：`act` 必填；`givenState` 可选（建立确定起始态）；`act` 里可以 `dispatch` / `awaitFact` / `awaitEvent` 等。
- `"ui"` / `"memory"` / `"persist"`——源命名是 page 的私有约定（不是框架规范）：framework 只按 sourceName 字符串匹配，不解释语义；如果你的 page 只有两源就少一个。
- 断言用 `copy(...)` 全等：`expectSource<T>("ui") { copy(...) }` 以当前快照为底、只改关心字段；`consistency("…") { facts -> … }` 做跨源一致性检查。
- `expectFeedFullyLoaded` 这类业务 shortcut 是 page 私有（Scenarios.kt 里的 `private fun ExpectFactScope.xxx`），orchestrator 不感知。
- `expectDependencies` 的通用原语是 `expectEvent(kind, name, matcher)` / `expectEventCount(kind, name, count)` / `expectNoEvent(kind, name)`——对 act 后的事件窗口做单次快照断言；业务别名（如有）在 page 侧封装。

baseline 相对断言（metamorphic 范式）：

```kotlin
expectFact { baseline ->                         // given 后、act 前的各源快照 Map<String, Any?>
    expectSource<FeedPersistFact>("persist") {
        copy(rowCount = (baseline["persist"] as FeedPersistFact).rowCount?.plus(1))
    }
}
```

- baseline 采集时机 = given 完成后、act 开始前；given 里做过放大操作的，baseline 是放大态，断言写相对表达式（如 `baseline × 2.0`）。
- given 必须建立确定起始态（用 reset 类 step，或 context 的 `baselineReset`——demo 在 `feedContext` 里用它清三源并 await 归零），避免批跑残留污染 baseline。
- 只断"关系"不写绝对值；不写零语义糖函数（派生表达式就地写在断言里）。
- 缺 `expectFact` 的 dependency-only scenario 不采集 baseline（零额外开销）。
- baseline 采集失败记 `baseline_snapshot` step FAIL 并跳过 act。

### 3. Catalog 注册

`<Page>Catalog.kt` 的 `<page>Scenarios` 列表追加一条 `ScenarioMeta`（真实样例见 [FeedCatalog.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/scenarios/FeedCatalog.kt)）：

```kotlin
val feedScenarios: List<ScenarioMeta> = listOf(
    ScenarioMeta(
        "refresh_shows_latest_feed",
        setOf("smoke", "feed"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runRefreshShowsLatestFeed,
    ),
    // 追加（无素材依赖，fixture 用默认空约束）：
    ScenarioMeta("my_scenario", setOf("smoke"), HostPage.DEMO, FixtureNeed.media(), run = ::runMyScenario),
)
```

`fixture` 参数缺省值就是 `FixtureNeed.media()`（空约束）。`--print-bootstrap-plan` 阶段按它反查素材，无素材满足则早报退出。

跑你的场景：

```bash
./scripts/run.sh --scenario my_scenario
./scripts/run.sh --tag smoke                # 按 tag 批量跑
./scripts/run.sh --all                      # 跑注册表里全部场景
```

### 注册新 command key

若 scenario 引入了**新的命令**，在 App 装配处的 `CommandRegistry` 里 `register`（demo 样例在 FeedApp.kt 的 `commands = CommandRegistry().apply { ... }`）：

- 键 = 稳定字符串（约定 `<域>.<动作>`，如 `Feed.Refresh` / `Item.ToggleLike`）；值 = handler lambda，从 `JsonObject` args 取参并调用业务入口。
- orchestrator scenario 侧 `dispatch("<key>", mapOf(...))` 用同一字符串，经 POST `/cmd` `{"key": "...", "args": {...}}` 送达；未注册的 key 设备端返 400。

## 三、（可选）补 Fact 字段 / step / expect shortcut

### 补 Fact 字段

新业务事实字段加到 page 的 `facts/*.kt`（demo 直接集中在 FeedFacts.kt），并补投影逻辑（JSON → typed Fact 的纯函数）。加字段规则：

1. 必须 nullable + 默认 `null`（向后兼容；`null` 表示该次快照未覆盖此字段）。
2. 必须在对应 `project*` 函数里补齐投影。
3. 必须至少一个 scenario 用到，否则删掉。
4. 拒绝塞"过程态"字段（如 `pendingLayout` / `isLoading` 类瞬时标志）——Fact 只装业务契约。

### 补 step

新业务动作放 page 的 `steps/<Domain>Steps.kt`，供多场景复用。step 本身只做 dispatch + 可选 barrier（`awaitFact` 等落定），不带 assertion。

### 补 expect shortcut

- 业务 shortcut（多源组合断言、业务别名等）都用 kotlin extension function 扩到 orchestrator 的 `ExpectFactScope` / `ExpectDependencyScope` 上（demo 的 `expectFeedFullyLoaded` 即 `ExpectFactScope` 扩展）。
- **不要往 orchestrator 里加**——orchestrator 只保留通用 `expectSource` / `consistency` / `expectEvent` / `expectEventCount` / `expectNoEvent` / `expectNoMatchingEvent`。

## 四、跨平台 fixtures 与素材门

- 素材清单在 `fixtures/e2e.example.json`（模板入库）+ `fixtures/e2e.local.json`（本机覆盖，gitignore），字段与三级读取说明见 [fixtures/README.md](../fixtures/README.md)。`media` 是**数组**，每个元素含 `device_path` / `host_path` / `attrs` 属性表。
- **media 按属性反查**：scenario 用 `FixtureNeed.media(constraints)` 声明属性要求（不点名素材），`--print-bootstrap-plan` 阶段由 orchestrator 反查 `media` 数组第一个满足 `attrs` 的条目，无满足则早报。当前语义：`minDurationMs`（常量 `FixtureNeed.MIN_DURATION_MS`）按下界（实际 ≥ 要求），其余 key 等值；素材缺某 key = 不满足（被排除）。
- **布尔能力属性要带显式约束**：当业务只依赖素材的某个布尔能力时（常量 `FixtureNeed.SUPPORTS_SPEED`，区分视频类素材 `"true"` 与图片类 `"false"`），声明约束必须带上它，否则反查可能选中不满足能力的素材，scenario 在真机上才炸。
- **点选已存工程状态**：除文件素材外，`FixtureNeed` 还支持按稳定 key 点选一份预先保存好的工程状态（`FixtureNeed.draft("<key>")`），不进属性反查（工程状态与文件素材属性是不同维度）。本机用 `e2e.local.json` 的 `.drafts.<key>` 存映射。
- 路径前缀（Android `files/` 根、iOS `Documents/` 根）由平台 bootstrap 脚本拼 `device_path` 相对段，orchestrator 不感知。注意 iOS 实际落盘为 `Documents/handeye/media/<文件名>`——只保留 `device_path` 的 basename、丢弃目录段（见 [device-ios/docs/bootstrap-contract.md](../device-ios/docs/bootstrap-contract.md) §1）；Android 按完整相对段落盘。素材两级兜底（设备上已存在 → 跳过；不存在 → 从 host 推送）在 `scripts/fetch_media.sh`（Android `adb push`）与 `scripts/bootstrap_ios.sh`（iOS afcclient）内。
- 接新平台时按平台补 bootstrap 脚本与装配逻辑，业务侧代码不感知平台差异。

## 五、验证清单（上 PR 前自检）

- [ ] 新加业务逻辑的 unit test 绿
- [ ] 触发链完整：command key → App 侧 CommandRegistry handler → 业务调用整链不断
- [ ] key 已注册：`curl -X POST http://127.0.0.1:<port>/cmd -d '{"key":"Feed.Refresh","args":{}}'` 返 `{"accepted":true}`（未注册 key 返 400）
- [ ] Fact 断言到达：`expectFact { expectSource<T>("…"){…} … }` 各源 PASS，`consistency` 无 diff
- [ ] 依赖断言命中：`expectDependencies { expectEvent(...) expectNoEvent(...) }` 在 act 后事件窗口里命中
- [ ] 场景 PASS：`./scripts/run.sh --scenario <name>` 输出 `[PASS] <name>`
- [ ] 批跑回归绿：`./scripts/run.sh --all` 全绿（批跑间有状态残留问题会在这一档暴露）
- [ ] golden 对拍（改了事件/快照结构时）：`./gradlew :conformance:run --args="diff <actual.jsonl> <golden.jsonl>"` exit 0

## 六、反模式（禁止事项）

- 不要在业务代码里绕过命令通道直接改状态——状态变更必须走 `dispatch` → `/cmd` → handler 这条链，scenario 才观察得到。
- 不要为新功能单独加事件采集分支——事件由 `EventRecorder` 订阅业务原生流（如 `registerFlow`）统一采集，业务代码不感知 e2e。
- 不要在业务里新引入异步通道（如 EventBus）——异步走现有事件/ effects 流即可。
- **不要往 orchestrator 加业务 shortcut**——业务专属断言都放 page 侧 extension；orchestrator 只保留通用原语（见 §三）。
- 不要复用其它 page 的 Fact / shortcut——page 隔离；跨 page 断言语义不同。

## 七、断言确定性与事件时序（跑批沉淀）

写 scenario 断言前必读的确定性模式，全部有真机踩坑实证：

1. **撤销/重做断言**：撤销语义是从某个历史快照重放撤销栈中除最后一条外的记录，恢复目标与「本 scenario 开始时的状态」无确定关系——批跑中工程状态跨 scenario 持久化累积，加载态会漂移。确定性写法：**双工作单元双记录**（两个独立工作单元各做一次真实修改并各自确认进栈），撤销回退第二个单元；首个单元的写命令须是对数据的全量覆盖写（整表替换类），重放结果与加载态无关。撤销执行时业务界面通常已关闭，列表类 UI 态在**重开界面**时才从数据真值重读，UI 断言放在重开之后。
2. **事件时序**：events 里命令事件在其 handler 的同步效应落盘**之后**才落盘。分析事件因果关系时以事件/快照记录为准，不要拿命令事件的落盘时刻当 handler 执行起点。
3. **异步事件断言先等落盘**：`expectDependencies` 对 act 后事件窗口做单次快照。同步记录的事件（观察者回调内落盘）可直接断言；经异步链路（渲染刷新等）落盘的事件，带它的断言时在 act 末尾先 `awaitEvent(kind, name)` 等其出现，再进断言窗口；经 `mainScope.launch` 异步发射的 effect 同理。窗口污染是双向的：目标事件可能迟于快照闭合，given 阶段写操作产生的事件也可能迟到落进 act 窗口——带异步事件断言的 scenario 两侧都要处理：act 末尾 `awaitEvent` 等目标事件落盘；given 里有写操作时，given 末尾先等前置刷新落定。`awaitEvent` 不能用在 given 侧：它按 act 起点游标之后的窗口匹配，act 开始前该游标尚未初始化，匹配范围是本 scenario 全量历史事件，判断不了前置刷新是否已落盘。
4. **共享基准量会漂移**：批跑中前序 scenario 的异步效应可能迟到落地，使共享的可变基准（游标、位置、计数等）在两次相邻操作之间漂移。依赖「两次操作读同一基准」的 scenario 需要先等基准静止（双采样一致）再连点，以消除漂移带来的不确定性。
5. **附带的位置移动不保证落点**：业务动作附带的定位/跳转会撞上在飞的状态重载被覆盖，落点不保证（真机实证：附带的定位落盘后被重载冲掉，后续读取落在目标之外）。scenario 需要处于指定状态时，用轮询 step 真等位置到位，不把业务动作附带的移动当确定性来源。
6. **act 内多次写操作，最终刷新可能被合并**：连续写操作会触发多次刷新，刷新在飞时后续写操作不产生独立的下游命令（只上报刷新开始事件）。因此命令序列断言只适合「act 内一次写操作、一次刷新」的场景；「多次修改保留最新」这类 case 用数据层断言（数据真值字段）验证最终值，不要断「最终一次的命令」（2026-09-22 真机实证）。
7. **写命令触发的刷新在飞时，播放类命令可能被状态机丢弃**：写命令后 `awaitEvent` 只保证命令已落盘，不保证渲染刷新完成；此时发播放命令撞上准备期会被丢弃，该次播放意图不重试就到不了目标位置。写后要继续播放的 scenario，在写命令 + `awaitEvent` 之后补等刷新完成（先观察到加载态离开就绪、再等它回到就绪；调用前提是上一轮刷新已结束），再发播放。「try/catch 补发一次」只吸收单次丢弃，不是确定性屏障（2026-09-22 真机实证）。
8. **同一意图有广播与真实执行两条路径，按 case 语义选**：广播路径只模拟「目标变化」，跳过物理过程；真实路径走完整链路（物理过程 → 触发广播 → 目标切换）。case 描述为「真实切换」、或要验证「选中态跟随物理过程」时，必须用真实路径——选中态跟随依赖物理过程，广播到不了这条链。仅测切换后的下游行为时，两条路径下游同源，可直接用广播（2026-09-22 真机实证）。
9. **跨单元操作的选中态跟随是必断项**：过程跨单元后选中态应跟随落到新单元（或按 case 语义清空）；仅断面板态/数值刷新抓不到这条链。断言用 ui 源的选中态字段与内容列表对应位置比对（跟随），或断选中态为空（清空）。

## 八、参考

- 协议与端点契约（/cmd、/source、/events、/health）：[protocol/spec.md](../protocol/spec.md)
- 协议一致性套件（结构校验 + golden 对拍）：[conformance/README.md](../conformance/README.md)
- 接入点清单（App 侧怎么装配 providers / commands）：[docs/integration-points.md](integration-points.md)
- fixtures 模板与素材反查：[fixtures/README.md](../fixtures/README.md)
- demo 真实样例：[Scenarios.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/scenarios/Scenarios.kt) · [FeedContext.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/FeedContext.kt) · [FeedFacts.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/FeedFacts.kt) · [FeedApp.kt](../demo-android/app/src/main/java/dev/handeye/demo/FeedApp.kt)
- 批跑总控：`./scripts/run.sh -h`
