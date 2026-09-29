package dev.handeye.demo.e2e

import dev.handeye.orchestrator.context.DiagnosticArtifact
import dev.handeye.orchestrator.context.DiagnosticContext
import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.context.E2eDefaults
import dev.handeye.orchestrator.dispatcher.HttpDispatcher
import dev.handeye.orchestrator.events.HttpEventsFetcher
import dev.handeye.orchestrator.factsource.HttpFactSource
import dev.handeye.orchestrator.http.OrchestratorHttpClient
import kotlin.time.Duration.Companion.seconds

/** 创建 Feed demo 的 host 侧 HTTP context —— orchestrator 框架与 device 端 debug server 的接线。 */
fun feedContext(baseUrl: String): E2eContext {
    val http = OrchestratorHttpClient(baseUrl)
    return E2eContext(
        dispatcher = HttpDispatcher(http, endpointPath = "/cmd"),
        factSources = mapOf(
            "ui" to HttpFactSource("ui", http, "/source?name=ui", FeedProjection::projectUi),
            "memory" to HttpFactSource("memory", http, "/source?name=memory", FeedProjection::projectMemory),
            "persist" to HttpFactSource("persist", http, "/source?name=persist", FeedProjection::projectPersist),
        ),
        eventsFetcher = HttpEventsFetcher(http, endpointPath = "/events"),
        healthCheck = { http.get("/health").statusCode == 200 },
        eventsReset = {
            val response = http.post("/reset", body = "{}", query = mapOf("mode" to "events"))
            check(response.statusCode == 200) {
                "POST /reset?mode=events failed: ${response.statusCode} ${response.body}"
            }
        },
        // 批跑状态隔离：framework 在 eventsReset 之后、scenario givenState 之前自动调用。
        // 清空内存缓存与持久化 DB 并等三源归零 —— 命令是异步执行的，必须 await 到干净起点，
        // 否则后续 givenState 与 baseline 采集会读到上一个场景的残留数据。
        baselineReset = {
            dispatch("Feed._Reset")
            awaitFact {
                val ui = it["ui"] as? FeedUiFact
                val memory = it["memory"] as? FeedMemoryFact
                val persist = it["persist"] as? FeedPersistFact
                ui?.listSize == 0 && memory?.entryCount == 0 && persist?.rowCount == 0
            }
        },
        diagnostics = DiagnosticContext(
            target = baseUrl,
            rawArtifacts = listOf(
                DiagnosticArtifact("ui-source.json") { http.get("/source", mapOf("name" to "ui")).body },
                DiagnosticArtifact("memory-source.json") { http.get("/source", mapOf("name" to "memory")).body },
                DiagnosticArtifact("persist-source.json") { http.get("/source", mapOf("name" to "persist")).body },
                DiagnosticArtifact("events.jsonl") { http.get("/events", mapOf("afterSeq" to "0")).body },
                DiagnosticArtifact("health.json") { http.get("/health").body },
            ),
        ),
        defaults = E2eDefaults(
            overallTimeout = 60.seconds,
            perSourceTimeout = 5.seconds,
        ),
    )
}
