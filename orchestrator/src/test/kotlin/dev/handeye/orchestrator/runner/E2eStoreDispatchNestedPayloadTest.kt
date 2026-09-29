package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.HttpDispatcher
import dev.handeye.orchestrator.events.HttpEventsFetcher
import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import com.sun.net.httpserver.HttpHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E2eStore.dispatchIntent` 复合参数递归转 JSON 单测。
 *
 * 覆盖复合 Intent（如 `Timeline.TrimEnd`）在 scenario 侧以嵌套 [Map] 表达时，被
 * [toJsonPayload] 递归转换成合法 [JsonObject] / [JsonArray]，而不是被降级成 Kotlin Map 的
 * `toString()` 字符串——后者会让 device 端 registry factory 无法把 `target.jsonObject`
 * 解析出来。断言直接读 [MockDebugServer.receivedIntents]，验证到达 wire 的 JSON 结构。
 */
class E2eStoreDispatchNestedPayloadTest {

    private lateinit var server: MockDebugServer
    private lateinit var store: E2eStore

    @BeforeTest
    fun setUp() {
        server = MockDebugServer(port = 0)
        server.readiness = true
        server.setIntentHandler(HttpHandler { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = Json.parseToJsonElement(body).jsonObject
            server.receivedIntents += "/cmd" to json
            val response = buildJsonObject {
                put("accepted", true)
            }
            MockDebugServer.writeJson(ex, 200, response.toString())
        })
        server.start()
        val http = OrchestratorHttpClient(server.baseUrl)
        store = E2eStore(
            E2eContext(
                dispatcher = HttpDispatcher(http),
                factSources = emptyMap(),
                eventsFetcher = HttpEventsFetcher(http),
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    /**
     * 断言：嵌套 [Map] 落成 [JsonObject]，嵌套 [List] 落成 [JsonArray]，primitive
     * 分支与既有 dispatch 契约完全一致（Long/Double/String/Boolean/null）。
     *
     * 用 `Timeline.TrimEnd` 的实际形状构造 payload：target 为复合 sealed target、newRange
     * / previousRange 为 Section 值对象、srcRange 缺席对应 null（`parseSectionOrNull`
     * 分支）。若递归转换失效，此断言会在 `target` 字段落进字符串 `"{kind=Overlay, id=..}"`
     * 时首先失败。
     */
    @Test
    fun dispatchIntent_nestedMap_isEncodedAsJsonObject() = runBlocking {
        store.dispatchIntent(
            intentClass = "Timeline.TrimEnd",
            args = mapOf(
                "target" to mapOf(
                    "kind" to "Overlay",
                    "id" to "speed_1",
                    "type" to "speed",
                ),
                "newRange" to mapOf("startMs" to 1000L, "endMs" to 3000L),
                "previousRange" to mapOf("startMs" to 800L, "endMs" to 3200L),
                "srcRange" to null,
            ),
        )

        assertEquals(1, server.receivedIntents.size, "预期恰好 1 次 /intent POST")
        val (path, body) = server.receivedIntents[0]
        assertEquals("/cmd", path)
        assertEquals("Timeline.TrimEnd", body["key"]?.jsonPrimitive?.contentOrNull)
        val args = body["args"]?.jsonObject ?: error("body.args 未落成 JsonObject")

        val target = args["target"]?.jsonObject
            ?: error("target 未落成 JsonObject，落进兜底 toString() 分支即失败：${args["target"]}")
        assertEquals("Overlay", target["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("speed_1", target["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("speed", target["type"]?.jsonPrimitive?.contentOrNull)

        val newRange = args["newRange"]?.jsonObject ?: error("newRange 未落成 JsonObject")
        assertEquals(1000L, newRange["startMs"]?.jsonPrimitive?.longOrNull)
        assertEquals(3000L, newRange["endMs"]?.jsonPrimitive?.longOrNull)

        val prev = args["previousRange"]?.jsonObject ?: error("previousRange 未落成 JsonObject")
        assertEquals(800L, prev["startMs"]?.jsonPrimitive?.longOrNull)
        assertEquals(3200L, prev["endMs"]?.jsonPrimitive?.longOrNull)

        assertTrue(args["srcRange"] is JsonNull, "null Section 值应落成 JsonNull，实为 ${args["srcRange"]}")
    }

    /**
     * 断言：List / Iterable 递归转为 [JsonArray]；数组内元素依旧走同一转换（primitive 保持类型，
     * 嵌套 Map 继续下沉为 JsonObject）。
     */
    @Test
    fun dispatchIntent_iterableAndPrimitiveMix_encoded() = runBlocking {
        store.dispatchIntent(
            intentClass = "Foo.Bar",
            args = mapOf(
                "ints" to listOf(1L, 2L, 3L),
                "objs" to listOf(mapOf("k" to "v")),
                "flag" to true,
                "ratio" to 0.5,
                "name" to "abc",
            ),
        )

        val (_, body) = server.receivedIntents.single()
        val args = body["args"]?.jsonObject ?: error("body.args 未落成 JsonObject")

        val ints = args["ints"]?.jsonArray ?: error("ints 未落成 JsonArray")
        assertEquals(listOf(1L, 2L, 3L), ints.map { it.jsonPrimitive.longOrNull })

        val objs = args["objs"]?.jsonArray ?: error("objs 未落成 JsonArray")
        assertEquals("v", objs.single().jsonObject["k"]?.jsonPrimitive?.contentOrNull)

        assertEquals(JsonPrimitive(true), args["flag"])
        assertEquals(0.5, args["ratio"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("abc", args["name"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * 断言：既有全 primitive scenario 契约完全兼容——null / Number / Boolean / String
     * 落成对应 JsonPrimitive 或 JsonNull，与本次 helper 引入前逐分支等价。
     */
    @Test
    fun dispatchIntent_primitiveOnly_backwardCompatible() = runBlocking {
        store.dispatchIntent(
            intentClass = "Speed.TrimSection",
            args = mapOf(
                "sectionId" to 3,
                "newStartTimeMs" to 100.5,
                "newEndTimeMs" to 999.5,
                "note" to null,
            ),
        )

        val (_, body) = server.receivedIntents.single()
        assertEquals("Speed.TrimSection", body["key"]?.jsonPrimitive?.contentOrNull)
        val args = body["args"]?.jsonObject ?: error("body.args 未落成 JsonObject")
        assertEquals(3L, args["sectionId"]?.jsonPrimitive?.longOrNull)
        assertEquals(100.5, args["newStartTimeMs"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(999.5, args["newEndTimeMs"]?.jsonPrimitive?.doubleOrNull)
        assertTrue(args["note"] is JsonNull, "null 应落成 JsonNull")
    }
}
