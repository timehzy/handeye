package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.HttpDispatcher
import dev.handeye.orchestrator.events.HttpEventsFetcher
import dev.handeye.orchestrator.factsource.HttpFactSource
import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.runner.hostVmTest
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task E1 · MockDebugServer 升级冒烟测试
 *
 * 目标：证明 [MockDebugServer] 6 个端点（`/health` / `/reset` / `/intent` / `/state` /
 * `/editmodule` / `/player`）能被 `hostVmTest` 的 6 阶段流程（PRE-RESET → GIVEN → ACT →
 * OBSERVE+PROJECT → ASSERT → REPORT）端到端跑通，且返回 schema 匹配 B1/B2/B3 契约。
 *
 * ## 覆盖点
 * 1. `/health` 返 200 → hostVmTest Phase 1 PRE-RESET 的 `checkHealth()` 不 throw
 * 2. `POST /reset?mode=events` 返 `{ok:true, resetEvents:true}` → Phase 1 `resetEvents()` 不 throw
 * 3. `POST /reset?mode=full` 返 400 `{ok:false, error:...}` —— B3 契约
 * 4. `POST /intent` 走 [MockDebugServer.setIntentHandler] 装的 handler，返 dispatched=true
 * 5. `GET /state` 返 `{"state": <JsonObject>}` → `E2eStore.fetchAndProjectUi` 投影 UiFact
 * 6. `GET /editmodule` 返 stub JsonObject → 投影 EditModuleFact
 * 7. `GET /player` 返 stub JsonObject → 投影 PlayerFact
 * 8. `expectFact` 通过 → 报告 pass=true
 *
 * 任一环节 schema 错、endpoint 缺、字段丢，都会让本测试 fail 并暴露具体环节。
 *
 */
class MockDebugServerE1SmokeTest {

    private lateinit var server: MockDebugServer

    @BeforeTest
    fun setUp() {
        server = MockDebugServer()
        server.readiness = true

        // Seed a minimal three-source snapshot compatible with BusinessFactProjection.
        server.currentState = buildJsonObject {
            put("aspectRatioState", buildJsonObject {
                put("selectedRatioIndex", 1)
                put("selectedRatio", "R16to9")
            })
        }
        server.currentEditModuleJson = buildJsonObject {
            put("ratio", "16:9")
        }
        server.currentPlayerJson = buildJsonObject {
            put("ok", true)
            put("renderParams", buildJsonObject {
                put("clipRenderParamsList", buildJsonArray {
                    add(buildJsonObject {
                        put("aspectRatioWidth", 16)
                        put("aspectRatioHeight", 9)
                        put("clipIndex", 0)
                    })
                })
            })
        }

        // 直接装最小 /intent handler：不 clobber currentState —— 冒烟测的重点是三源 endpoint
        // schema 一致性，不是 default handler 的 snapshot 覆写行为
        server.setIntentHandler(HttpHandler { ex ->
            val body = buildJsonObject {
                put("dispatched", true)
                put("consumerTag", "smoke")
                put("stateSnapshot", buildJsonObject { put("snapshot", "SmokeState()") })
            }.toString()
            MockDebugServer.writeJson(ex, 200, body)
        })
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun hostVmTest_drives_mock_through_health_reset_intent_and_three_source_observe() {
        val http = OrchestratorHttpClient(server.baseUrl)
        val ctx = E2eContext(
            dispatcher = HttpDispatcher(http),
            factSources = mapOf(
                "first" to HttpFactSource("first", http, "/state") { body ->
                    body["state"]!!.jsonObject
                },
                "second" to HttpFactSource("second", http, "/editmodule") { it },
                "third" to HttpFactSource("third", http, "/player") { it },
            ),
            eventsFetcher = HttpEventsFetcher(http),
            healthCheck = { http.get("/health").statusCode == 200 },
            eventsReset = {
                check(http.post("/reset", "{}", mapOf("mode" to "events")).statusCode == 200)
            },
        )
        val result = hostVmTest(
            name = "smoke_mock_wire",
            ctx = ctx,
            block = {
                act { dispatch("Smoke.Intent") }
                expectFact {
                    expectSource<kotlinx.serialization.json.JsonObject>("first") {
                        check(this["aspectRatioState"]!!.jsonObject["selectedRatioIndex"]!!.jsonPrimitive.content == "1")
                        this
                    }
                    expectSource<kotlinx.serialization.json.JsonObject>("second") {
                        check(this["ratio"]!!.jsonPrimitive.content == "16:9")
                        this
                    }
                    expectSource<kotlinx.serialization.json.JsonObject>("third") {
                        val clip = this["renderParams"]!!.jsonObject["clipRenderParamsList"]!!.jsonArray[0].jsonObject
                        check(clip["aspectRatioWidth"]!!.jsonPrimitive.content == "16")
                        this
                    }
                }
            },
        )
        assertTrue(result.pass, "smoke test should PASS. Actual report:\n${result.reporter.formatTree()}")
    }

    @Test
    fun reset_with_invalid_mode_returns_400() {
        // 直接走 HTTP 验 mode=full 走 B3 契约 400 路径 —— hostVmTest 只用默认 mode=events，
        // 需要独立验证 mode 校验分支。
        val http = OrchestratorHttpClient(server.baseUrl)
        val resp = http.post("/reset", body = "", query = mapOf("mode" to "full"))
        assertTrue(resp.statusCode == 400, "expected 400 for mode=full, got ${resp.statusCode} body=${resp.body}")
        assertTrue(resp.body.contains("invalid mode"), "expected error message contains 'invalid mode', got ${resp.body}")
    }

    @Test
    fun pushEvent_appends_to_events_list() {
        val evt = buildJsonObject {
            put("t", 42)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        }
        server.pushEvent(evt)
        val http = OrchestratorHttpClient(server.baseUrl)
        val resp = http.get("/events")
        assertTrue(resp.statusCode == 200, "expected 200 from /events, got ${resp.statusCode}")
        // Payload 里必须能看到刚 push 的 event —— events endpoint 直接把 [events] 列表序列化返回，
        // 因此 body 里出现 "KMPBizPlayer.play" 表示 pushEvent 正确把事件放进列表。
        assertTrue(resp.body.contains("KMPBizPlayer.play"), "expected pushed event visible in /events body: ${resp.body}")
    }

    @Test
    fun events_with_invalid_cursor_returns_400() {
        val http = OrchestratorHttpClient(server.baseUrl)

        assertEquals(400, http.get("/events", mapOf("afterSeq" to "bad")).statusCode)
        assertEquals(400, http.get("/events", mapOf("since" to "bad")).statusCode)
    }

    @Test
    fun events_filters_by_kinds_csv() {
        server.pushEvent(event(t = 10, kind = "intent", name = "A"))
        server.pushEvent(event(t = 20, kind = "effect", name = "B"))
        server.pushEvent(event(t = 30, kind = "state", name = "C"))

        val events = responseEvents(mapOf("kinds" to "intent,effect"))

        assertEquals(listOf("A", "B"), events.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun events_combines_since_and_after_seq_filters() {
        server.pushEvent(event(t = 20, kind = "intent", name = "beforeSeq"))   // seq=1
        server.pushEvent(event(t = 10, kind = "intent", name = "beforeSince")) // seq=2
        server.pushEvent(event(t = 20, kind = "intent", name = "matched"))     // seq=3

        val events = responseEvents(mapOf("since" to "20", "afterSeq" to "1"))

        assertEquals(listOf("matched"), events.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun pushEvent_overrides_explicit_seq_and_reset_restarts_from_one() {
        server.pushEvent(buildJsonObject {
            put("t", 10)
            put("seq", 99)
            put("kind", "intent")
        })
        server.pushEvent(buildJsonObject {
            put("t", 20)
            put("seq", 1)
            put("kind", "effect")
        })
        assertEquals(listOf(1L, 2L), responseEvents().map { it["seq"]!!.jsonPrimitive.long })

        val http = OrchestratorHttpClient(server.baseUrl)
        assertEquals(200, http.post("/reset", "{}", mapOf("mode" to "events")).statusCode)
        server.pushEvent(buildJsonObject {
            put("t", 0)
            put("seq", 500)
            put("kind", "state")
        })

        assertEquals(listOf(1L), responseEvents().map { it["seq"]!!.jsonPrimitive.long })
    }

    private fun event(t: Long, kind: String, name: String) = buildJsonObject {
        put("t", t)
        put("kind", kind)
        put("name", name)
    }

    private fun responseEvents(query: Map<String, String> = emptyMap()) =
        Json.parseToJsonElement(OrchestratorHttpClient(server.baseUrl).get("/events", query).body)
            .jsonObject["events"]!!.jsonArray.map { it.jsonObject }
}
