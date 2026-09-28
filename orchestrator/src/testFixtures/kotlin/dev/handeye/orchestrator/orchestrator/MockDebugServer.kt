package dev.handeye.orchestrator.orchestrator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.Collections

/**
 * 用 `com.sun.net.httpserver.HttpServer` 起 7 个 handler（`/health` / `/intent` / `/events`
 * / `/state` / `/editmodule` / `/player` / `/reset` / `/wait-for`），模拟真实 Android debug
 * server 的最小行为——scenario 单测（select_ratio / apply_pip / apply_beauty / play_pause 等）
 * 用它跑 dispatch → events → 三源 fact 全链路。
 *
 * ## 何时用
 * 在 orchestrator 的 scenario 单测里：`@BeforeTest` 里 `MockDebugServer()` 起服务、`readiness =
 * true` 打开 hook、按需 seed [currentState] / [currentEditModuleJson] / [currentPlayerJson]、
 * 用 [setIntentHandler] 装 `HttpHandler { ex -> ... }` 覆盖默认 /intent 响应；`@AfterTest`
 * 里 `stop()`。case 需要"故意让 /intent 不产 event"或"故意 snapshot mismatch"这类
 * fault-injection 时，同样走 [setIntentHandler] 换掉 handler。默认 /intent handler 直接返
 * 500 提示未装 handler，避免 case 漏装时超时定位困难。
 *
 * ## 何时不用
 * - 真机端到端跑（`e2e.sh` 走 gradle `:e2e-scenarios:run` 直连真机 debug server）——scenario 函数
 *   本身跟本类无关，不会被拉入
 * - 场景需要额外的 handler（如 `/logs`、`/config`）——本类只覆盖 3 个已落地场景收敛出的
 *   handler 骨架，新端点先按 plan「跨阶段约定」的 3 条抽象触发条件评估是否扩本类
 * - 需要不同初始化顺序、共享 fixture 之外的构造——先在 case 里手写 HttpServer，第 4 个
 *   case 稳定后再回来抽
 */
class MockDebugServer(port: Int = 0) {

    /** hook readiness——`/health` 按此返 200/503。case 在 dispatch 前手动置 true。 */
    var readiness: Boolean = false

    /**
     * events.jsonl 序列的可变列表——`/events` 直接把它序列化返回。default handler 里 append
     * event（3 类 kind），case 自定义 handler 时也直接改这个列表。
     *
     * 走 [Collections.synchronizedList]：`HttpServer` 用 JDK 线程池调 `eventsHandler`，同时
     * 测试线程 / 自定义 handler 会通过 [pushEvent] 或直接改列表并发写。
     * 读用 [snapshotEvents] 语义包起 `synchronized(events)` 拿一次性快照，避免 iterator 遍历
     * 时其他线程 add 触发 `ConcurrentModificationException`。
     */
    val events: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    /**
     * `/intent` 收到的请求记录——供 dispatcher 这类需要断言「请求确实打到 `/intent` 且 body 含
     * class/args」的测试使用。case 自定义 handler 里可读取请求体后追加到本列表。
     *
     * Pair 约定：`first`=请求 path（`/intent`），`second`=解析后的 JsonObject body。
     */
    val receivedIntents: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
    private var eventSeq: Long = 0L

    /**
     * `/state` 端点返回的 state JsonObject —— endpoint 会 wrap 成 `{"state": <此对象>}` 返回。
     * D-series hostVmTest scenario 直接吃这个 JsonObject 走 `BusinessFactProjection.projectUiState`
     * 投影。
     *
     * 用法：hostVmTest 场景
     * ```
     * server.currentState = buildJsonObject { put("aspectRatioState", ...) }
     * ```
     * → `/state` 返 `{"state": {"aspectRatioState": {...}}}`，`E2eStore.observeThreeSourceFacts`
     * 拉后走 `BusinessFactProjection.projectUiState` 投影。
     */
    var currentState: JsonObject = buildJsonObject { }

    /**
     * `/editmodule` 端点返回体，服务三源投影里 `E2eStore.fetchAndProjectEdit` 的 `GET /editmodule`。
     *
     * 默认空对象——投影后 [EditModuleFact] 全 null。case 需要 assert `editModule.aspectRatio`
     * 等字段时按 [BusinessFactProjection.projectEditModule] 输入约定 seed（可以是顶层
     * `{"ratio": "16:9", "filters": [...], ...}` 或包装形态 `{"editModule": {...}}`）。
     */
    var currentEditModuleJson: JsonObject = buildJsonObject { }

    /**
     * `/player` 端点返回体，服务三源投影里 `E2eStore.fetchAndProjectPlayer` 的 `GET /player`。
     *
     * 默认 `{"ok": true, "renderParams": null}` —— [BusinessFactProjection.projectPlayer] 见
     * `renderParams == null` 会返 `PlayerFact()` 全 null。case 需要 assert `player.renderRatio`
     * 等字段时按投影输入约定 seed 结构化 `renderParams.clipRenderParamsList[0].aspectRatioWidth/Height`。
     */
    var currentPlayerJson: JsonObject = buildJsonObject {
        put("ok", true)
        put("renderParams", JsonNull)
    }

    /**
     * `/health` 端点默认响应里的 pid（现场保留字段，orchestrator FAIL 时会读）——case 可以在 setup 后写。
     * 装配器实际会填 `android.os.Process.myPid()`，mock 里用固定值方便断言。
     */
    var pid: Int = 4242

    /**
     * `/health` 端点默认响应里的 logFilePath（现场保留字段，orchestrator FAIL 时会读）——case 可以在 setup 后写。
     * 默认给个明显是 mock 的路径避免误读成真实产物。
     */
    var logFilePath: String = "/tmp/mvi-e2e-mock/events.jsonl"

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    /**
     * 服务端 baseUrl（`http://127.0.0.1:<port>`）——[start] 前也能读，端口在 create 时就分配好。
     * 传 `port = 0` 时 JDK 会分配空闲端口，避免多 case 并跑撞端口。
     */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    private var intentHandler: HttpHandler = HttpHandler { ex ->
        // 未 install default handler 且未 setIntentHandler 时的兜底：让 dispatch 500，
        // 便于 case 在 setup 里忘记装 handler 时立刻暴露而不是超时
        writeJson(ex, 500, """{"error":"no /intent handler installed"}""")
    }

    /**
     * `/editmodule` handler。默认按 [currentEditModuleJson] 返 200；
     * fault-injection case（如 baseline 采集期返 500）用 [setEditModuleHandler] 热替换。
     */
    private var editModuleHandler: HttpHandler = HttpHandler { ex ->
        writeJson(ex, 200, currentEditModuleJson.toString())
    }

    /**
     * `/wait-for` handler。默认返 satisfied=true / elapsedMs=0——orchestrator 侧的
     * [waitForState] / [waitForEvent] 单测只关心「有没有正确 POST /wait-for + body 结构对不对」，
     * 端点内部 predicate DSL 由 commonTest [WaitForEndpointTest] 覆盖，无需在 mock 里重跑一遍。
     *
     * 需要断言 orchestrator 侧发的 body 时，用 [setWaitForHandler] 装自定义 handler 直接读
     * request body 断言。
     */
    private var waitForHandler: HttpHandler = HttpHandler { ex ->
        writeJson(ex, 200, """{"satisfied":true,"elapsedMs":0}""")
    }

    /**
     * `/events` handler。默认按真端 `EventsEndpoint` 契约组合应用 `since` / `afterSeq` / `kinds`
     * 过滤并返回 [events]。fault-injection case（如 /events 返 500 后验证 tailer 是否 abort）用
     * [setEventsHandler] 热替换。
     */
    private var eventsHandler: HttpHandler = HttpHandler { ex ->
        val query = ex.requestURI.query.orEmpty()
        val sinceRaw = queryParam(query, "since")
        val sinceMs = if (sinceRaw.isNullOrBlank()) 0L else sinceRaw.toLongOrNull()
        if (sinceMs == null) {
            writeJson(ex, 400, buildJsonObject { put("error", "invalid since: $sinceRaw") }.toString())
            return@HttpHandler
        }
        val afterSeqRaw = queryParam(query, "afterSeq")
        val afterSeq = if (afterSeqRaw.isNullOrBlank()) null else afterSeqRaw.toLongOrNull()
        if (afterSeqRaw?.isNotBlank() == true && afterSeq == null) {
            writeJson(ex, 400, buildJsonObject { put("error", "invalid afterSeq: $afterSeqRaw") }.toString())
            return@HttpHandler
        }
        val kindSet = queryParam(query, "kinds")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val filtered = snapshotEvents().filter { event ->
            val t = event["t"]?.jsonPrimitive?.longOrNull ?: return@filter false
            val seq = event["seq"]?.jsonPrimitive?.longOrNull
            val kind = event["kind"]?.jsonPrimitive?.contentOrNull ?: return@filter false
            t >= sinceMs &&
                (afterSeq == null || (seq != null && seq > afterSeq)) &&
                (kindSet == null || kind in kindSet)
        }
        val body = buildJsonObject {
            put("events", JsonArray(filtered))
        }.toString()
        writeJson(ex, 200, body)
    }

    /**
     * 从 [events] 拿一份并发安全的快照——`synchronizedList` 允许并发单点操作，但 iterator 遍历
     * 需外部同步，这里在 `synchronized(events)` 块里 copy 出一个不可变 list 给 handler 使用。
     */
    private fun snapshotEvents(): List<JsonObject> = synchronized(events) { events.toList() }

    private fun queryParam(query: String, name: String): String? {
        for (param in query.split("&")) {
            val eq = param.indexOf('=')
            if (eq <= 0) continue
            if (param.substring(0, eq) != name) continue
            return param.substring(eq + 1)
        }
        return null
    }

    init {
        server.createContext("/health", HttpHandler { ex ->
            // /health 响应带 pid + logFilePath，orchestrator FAIL 现场保留会读这两字段。
            val (status, body) = if (readiness) {
                200 to """{"ok":true,"hookInstalled":true,"pid":$pid,"logFilePath":"$logFilePath"}"""
            } else {
                503 to """{"ok":false,"hookInstalled":false,"pid":null,"logFilePath":null}"""
            }
            writeJson(ex, status, body)
        })
        // /intent 走 wrapper 转发到 intentHandler，允许后置 setIntentHandler 热替换
        server.createContext("/intent", HttpHandler { ex -> intentHandler.handle(ex) })
        server.createContext("/events", HttpHandler { ex -> eventsHandler.handle(ex) })
        server.createContext("/state") { ex ->
            // B1 [StateEndpoint] 契约：`body["state"]` 是 JsonObject。
            val body = buildJsonObject {
                put("state", currentState)
            }.toString()
            writeJson(ex, 200, body)
        }
        server.createContext("/editmodule", HttpHandler { ex -> editModuleHandler.handle(ex) })
        server.createContext("/player") { ex ->
            writeJson(ex, 200, currentPlayerJson.toString())
        }
        server.createContext("/reset") { ex ->
            // 匹配 B3 [ResetEndpoint] 契约：只接 mode=events；其它值 400；缺 mode 兜底 events。
            val query = ex.requestURI.rawQuery.orEmpty()
            val mode = query.split("&")
                .mapNotNull { param ->
                    val eq = param.indexOf('=')
                    if (eq <= 0) null else param.substring(0, eq) to param.substring(eq + 1)
                }
                .firstOrNull { it.first == "mode" }?.second
                ?: DEFAULT_RESET_MODE
            if (mode != DEFAULT_RESET_MODE) {
                writeJson(ex, 400, buildJsonObject {
                    put("ok", false)
                    put("error", "invalid mode: $mode")
                }.toString())
            } else {
                synchronized(events) {
                    events.clear()
                    eventSeq = 0L
                }
                writeJson(ex, 200, buildJsonObject {
                    put("ok", true)
                    put("resetEvents", true)
                    put("elapsedMs", 0)
                }.toString())
            }
        }
        // /wait-for 走 wrapper 转发到 waitForHandler，允许后置 setWaitForHandler 热替换
        server.createContext("/wait-for", HttpHandler { ex -> waitForHandler.handle(ex) })
    }

    /** 启动 server（同步返回后 [baseUrl] 已可用）。 */
    fun start() {
        server.start()
    }

    /** 停止 server，等待 0s 立即释放端口。case `@AfterTest` 调。 */
    fun stop() {
        server.stop(0)
    }

    /**
     * 替换 /intent handler——fault-injection case 用（"故意不产 event"、"故意 snapshot mismatch"
     * 等）。case 自己写完整 handler 体，包含 writeJson response，直接读写 [events] /
     * [currentState] 字段。
     */
    fun setIntentHandler(handler: HttpHandler) {
        intentHandler = handler
    }

    /**
     * 替换 /wait-for handler——orchestrator 侧 [waitForState] / [waitForEvent] 单测里想读
     * request body 断言时用，或想模拟 satisfied=false / 4xx / 5xx 时用。
     *
     * case 自己写完整 handler 体，包含 [writeJson] 响应。默认 handler 恒返
     * `{"satisfied":true,"elapsedMs":0}`，覆盖大多数不需要精确断言 body 的 case。
     */
    fun setWaitForHandler(handler: HttpHandler) {
        waitForHandler = handler
    }

    /**
     * 替换 /events handler——fault-injection case 用（返 500 / 4xx / 非法 body）。默认按
     * [events] 列表返 200；HttpEventsFetcher / orchestrator 异常路径单测可能用到此能力。
     *
     * case 自己写完整 handler 体，包含 [writeJson] 响应；也可以在自定义 handler 内调用
     * [MockDebugServer.Companion.writeJson] 复用序列化。
     */
    fun setEventsHandler(handler: HttpHandler) {
        eventsHandler = handler
    }

    /**
     * 替换 /editmodule handler——fault-injection case 用（返 500 / 非法 body）。
     * 默认按 [currentEditModuleJson] 返 200。
     */
    fun setEditModuleHandler(handler: HttpHandler) {
        editModuleHandler = handler
    }

    /**
     * 往 [events] 追加一条事件的便捷方法——供 D-series 场景测试注入 playerCmd / editModuleCmd /
     * effect 事件用。与真端一致统一覆盖注入单调 `seq`，调用方传入的 `seq` 不生效。
     */
    fun pushEvent(event: JsonObject) {
        synchronized(events) {
            events += buildJsonObject {
                event.forEach { (key, value) -> put(key, value) }
                put("seq", ++eventSeq)
            }
        }
    }

    private fun writeJson(ex: HttpExchange, status: Int, body: String) {
        Companion.writeJson(ex, status, body)
    }

    companion object {
        /** [ResetEndpoint] 唯一支持的 mode——保持跟 device 侧契约一致。 */
        private const val DEFAULT_RESET_MODE = "events"

        /**
         * 写 JSON 响应的通用 helper——供 case 通过 [setIntentHandler] 装 fault-injection
         * handler 时直接复用，不再各 Test 类重复贴一份 writeJson。
         */
        fun writeJson(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
