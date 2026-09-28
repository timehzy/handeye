package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 校验 [E2eContext.baselineReset] 在 scenario 执行链中的调用时序与失败短路语义。
 *
 * baselineReset 是「批跑状态隔离」钩子：framework 在 eventsReset 之后、givenState 之前
 * 自动执行；用于把 host page 全部可变业务状态归位到已知干净起点。
 */
class HostVmTestBaselineResetTest {

    private class RecordingDispatcher : Dispatcher {
        val calls: MutableList<String> = mutableListOf()
        override suspend fun dispatch(action: String, payload: JsonObject): DispatchResult {
            calls += action
            return DispatchResult(true, null, null, null)
        }
    }

    private val noopEvents = object : EventsFetcher {
        override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) = emptyList<E2eEvent>()
    }

    @Test
    fun `baselineReset runs before givenState and act`() {
        val dispatcher = RecordingDispatcher()
        val ctx = E2eContext(
            dispatcher = dispatcher,
            factSources = emptyMap(),
            eventsFetcher = noopEvents,
            baselineReset = {
                dispatch("BASELINE.step1")
                dispatch("BASELINE.step2")
            },
        )

        val result = hostVmTest(
            name = "baseline_ordering",
            ctx = ctx,
            block = {
                act {
                    dispatch("SCENARIO.act")
                }
            },
        )

        assertTrue(result.pass)
        assertEquals(listOf("BASELINE.step1", "BASELINE.step2", "SCENARIO.act"), dispatcher.calls)
    }

    @Test
    fun `null baselineReset is skipped without step marker`() {
        val dispatcher = RecordingDispatcher()
        val ctx = E2eContext(
            dispatcher = dispatcher,
            factSources = emptyMap(),
            eventsFetcher = noopEvents,
            baselineReset = null,
        )

        val result = hostVmTest(
            name = "baseline_absent",
            ctx = ctx,
            block = {
                act {
                    dispatch("SCENARIO.act")
                }
            },
        )

        assertTrue(result.pass)
        assertEquals(listOf("SCENARIO.act"), dispatcher.calls)
        assertFalse(
            result.reporter.formatTree().contains("baseline_reset"),
            "baseline_reset step 不应出现在 reporter 树里（无钩子无 step）",
        )
    }

    @Test
    fun `baselineReset failure short-circuits givenState and act`() {
        val dispatcher = RecordingDispatcher()
        val ctx = E2eContext(
            dispatcher = dispatcher,
            factSources = emptyMap(),
            eventsFetcher = noopEvents,
            baselineReset = {
                dispatch("BASELINE.step_before_throw")
                error("simulated baseline failure")
            },
        )

        val result = hostVmTest(
            name = "baseline_failure_shortcircuit",
            ctx = ctx,
            block = {
                act {
                    dispatch("SCENARIO.act_should_not_run")
                }
            },
        )

        assertFalse(result.pass)
        assertEquals(listOf("BASELINE.step_before_throw"), dispatcher.calls)
        assertTrue(result.reporter.formatTree().contains("baseline_reset"))
    }
}
