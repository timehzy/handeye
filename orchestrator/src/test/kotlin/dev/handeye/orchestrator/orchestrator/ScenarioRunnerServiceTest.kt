package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.context.DiagnosticContext
import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.runner.ScenarioResult
import dev.handeye.orchestrator.runner.SoftAssertionReporter
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioRunnerServiceTest {

    @Test
    fun `runOne executes registered scenario with context target and maps result`() {
        var receivedTarget: String? = null
        val registry = DefaultScenarioRegistry().apply {
            register(
                ScenarioMeta("registered", setOf("test"), HostPage.DEMO) { target ->
                    receivedTarget = target
                    ScenarioResult(
                        name = "registered",
                        pass = false,
                        reporter = SoftAssertionReporter().apply {
                            step("passed", pass = true)
                            step("failed", pass = false, failReason = "expected")
                        },
                        elapsedMs = 12L,
                    )
                },
            )
        }

        val record = ScenarioRunner(registry).runOne(context("custom-target"), "registered")

        assertEquals("custom-target", receivedTarget)
        assertEquals("registered", record.scenarioName)
        assertFalse(record.passed)
        assertEquals(1, record.failedSoft)
        assertTrue(record.finishedAtMs >= record.startedAtMs)
    }

    @Test
    fun `runStream emits registered scenario record`() = runBlocking {
        val registry = DefaultScenarioRegistry().apply {
            register(
                ScenarioMeta("streamed", setOf("test"), HostPage.DEMO) {
                    ScenarioResult(
                        name = "streamed",
                        pass = true,
                        reporter = SoftAssertionReporter(),
                        elapsedMs = 1L,
                    )
                },
            )
        }

        val record = ScenarioRunner(registry).runStream(context("stream-target"), "streamed").single()

        assertEquals("streamed", record.scenarioName)
        assertTrue(record.passed)
    }

    private fun context(target: String): E2eContext =
        E2eContext(
            dispatcher = object : Dispatcher {
                override suspend fun dispatch(action: String, payload: JsonObject) =
                    DispatchResult(true, null, null, null)
            },
            factSources = emptyMap(),
            eventsFetcher = object : EventsFetcher {
                override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) =
                    emptyList<E2eEvent>()
            },
            diagnostics = DiagnosticContext(target = target),
        )
}
