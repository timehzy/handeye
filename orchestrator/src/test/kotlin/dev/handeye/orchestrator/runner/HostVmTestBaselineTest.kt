package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.factsource.FactSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * hostVmTest baseline 采集语义单测 —— Phase 2.5（given 后、act 前）快照是关系断言的基准点。
 *
 * 覆盖：
 * - baseline 反映 post-given / pre-act 的态（既不吃 act 的变化，也不漏 given 的变化）
 * - baseline 采集失败（/editmodule 500）→ baseline_snapshot step FAIL + act 不执行
 * - expectFact=null 的 dependency-only scenario 不采集 baseline（/editmodule 500 也无影响）
 */
class HostVmTestBaselineTest {

    private data class CounterFact(val value: Int)

    @Test
    fun `baseline reflects post-given pre-act state`() {
        var current = CounterFact(30)
        val ctx = context(
            factSource = object : FactSource<CounterFact> {
                override val name = "counter"
                override suspend fun fetch(): CounterFact = current
            },
            onDispatch = { action ->
                current = when (action) {
                    "To15" -> CounterFact(15)
                    "To7" -> CounterFact(7)
                    else -> current
                }
            }
        )

        var capturedBaseline: Map<String, Any?>? = null
        val result = hostVmTest(
            name = "baseline-timing",
            ctx = ctx,
            block = {
                givenState { dispatch("To15") }
                act { dispatch("To7") }
                expectFact { baseline ->
                    capturedBaseline = baseline
                    expectSource<CounterFact>("counter") { copy(value = 7) }
                }
            },
        )

        assertTrue(result.pass, "预期 PASS，实际:\n${result.reporter.formatTree()}")
        assertEquals(
            CounterFact(15),
            capturedBaseline?.get("counter"),
            "baseline 应是 given 后、act 前的态（15），不是初始（30）也不是 final（7）",
        )
    }

    @Test
    fun `baseline capture failure reports baseline_snapshot and skips act`() {
        var intentCalls = 0
        val ctx = context(
            factSource = object : FactSource<CounterFact> {
                override val name = "counter"
                override suspend fun fetch(): CounterFact? = null
            },
            onDispatch = { intentCalls++ },
        )

        val result = hostVmTest(
            name = "baseline-fail",
            ctx = ctx,
            block = {
                act { dispatch("Noop") }
                expectFact {
                    expectSource<CounterFact>("counter") { this }
                }
            },
        )

        assertFalse(result.pass, "baseline 采集失败应 FAIL")
        assertTrue(
            result.reporter.formatTree().contains("✗ baseline_snapshot"),
            "预期 baseline_snapshot step FAIL，实际:\n${result.reporter.formatTree()}",
        )
        assertEquals(0, intentCalls, "baseline 失败时 act 不应执行")
    }

    @Test
    fun `dependency-only scenario skips baseline capture`() {
        var fetchCalls = 0
        val ctx = context(
            factSource = object : FactSource<CounterFact> {
                override val name = "counter"
                override suspend fun fetch(): CounterFact? {
                    fetchCalls++
                    return null
                }
            },
        )

        val result = hostVmTest(
            name = "baseline-skipped",
            ctx = ctx,
            block = {
                act { dispatch("Noop") }
                expectDependencies { }
            },
        )

        assertTrue(result.pass, "expectFact=null 时不采集 baseline")
        assertEquals(0, fetchCalls)
    }

    private fun context(
        factSource: FactSource<*>,
        onDispatch: (String) -> Unit = {},
    ) = E2eContext(
        dispatcher = object : Dispatcher {
            override suspend fun dispatch(action: String, payload: JsonObject): DispatchResult {
                onDispatch(action)
                return DispatchResult(
                    dispatched = true,
                    consumerTag = "test",
                    stateSnapshot = null,
                    errorMessage = null,
                )
            }
        },
        factSources = mapOf(factSource.name to factSource),
        eventsFetcher = object : EventsFetcher {
            override suspend fun fetchAfter(
                seq: Long,
                kinds: Set<String>?,
                limit: Int,
            ) = emptyList<dev.handeye.orchestrator.events.E2eEvent>()
        },
    )
}
