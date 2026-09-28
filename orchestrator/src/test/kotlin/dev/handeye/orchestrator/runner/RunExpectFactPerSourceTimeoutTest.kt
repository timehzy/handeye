package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.factsource.FactSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Generic source polling keeps each source's deadline independent. */
class RunExpectFactPerSourceTimeoutTest {

    private data class TestFact(val value: Int)

    @Test
    fun `failed source fetch does not abort a healthy pending source`() {
        var brokenFetches = 0
        val broken = object : FactSource<TestFact> {
            override val name = "broken"

            override suspend fun fetch(): TestFact {
                brokenFetches++
                if (brokenFetches > 1) error("broken source")
                return TestFact(0)
            }
        }
        val healthy = object : FactSource<TestFact> {
            override val name = "healthy"
            override suspend fun fetch() = TestFact(0)
        }

        val result = hostVmTest(
            name = "isolated-source-failure",
            ctx = context(broken, healthy),
            block = {
                act { dispatch("Noop") }
                expectFact {
                    expectSource<TestFact>("broken") { copy(value = 1) }
                    expectSource<TestFact>("healthy") { copy(value = 0) }
                }
            },
        )

        val report = result.reporter.formatTree()
        assertFalse(result.pass)
        assertTrue(report.contains("✗ source[broken]"), report)
        assertTrue(report.contains("✓ source[healthy]"), report)
    }

    @Test
    fun `short failing source timeout is not extended by long passing source timeout`() {
        val result = hostVmTest(
            name = "short-fail-long-pass",
            ctx = context(primary = 99, secondary = 0),
            block = {
                act { dispatch("Noop") }
                expectFact {
                    expectSource<TestFact>("primary", timeout = 500.milliseconds) { copy(value = 1) }
                    expectSource<TestFact>("secondary", timeout = 2_000.milliseconds) { copy(value = 0) }
                }
            },
        )

        assertFalse(result.pass)
        assertTrue(result.reporter.formatTree().contains("✗ source[primary]"))
        assertTrue(result.elapsedMs < 1_500, "elapsed=${result.elapsedMs}ms")
    }

    @Test
    fun `second source can own the short independent timeout`() {
        val result = hostVmTest(
            name = "long-pass-short-fail",
            ctx = context(primary = 1, secondary = 99),
            block = {
                act { dispatch("Noop") }
                expectFact {
                    expectSource<TestFact>("primary", timeout = 2_000.milliseconds) { copy(value = 1) }
                    expectSource<TestFact>("secondary", timeout = 500.milliseconds) { copy(value = 0) }
                }
            },
        )

        assertFalse(result.pass)
        assertTrue(result.reporter.formatTree().contains("✗ source[secondary]"))
        assertTrue(result.elapsedMs < 1_500, "elapsed=${result.elapsedMs}ms")
    }

    @Test
    fun `all sources satisfying immediately returns fast`() {
        val result = hostVmTest(
            name = "all-pass",
            ctx = context(primary = 1, secondary = 0),
            block = {
                act { dispatch("Noop") }
                expectFact {
                    expectSource<TestFact>("primary", timeout = 3_000.milliseconds) { copy(value = 1) }
                    expectSource<TestFact>("secondary", timeout = 3_000.milliseconds) { copy(value = 0) }
                }
            },
        )

        assertTrue(result.pass, result.reporter.formatTree())
        assertTrue(result.elapsedMs < 1_000, "elapsed=${result.elapsedMs}ms")
    }

    private fun context(primary: Int, secondary: Int): E2eContext {
        fun source(name: String, value: Int) = object : FactSource<TestFact> {
            override val name = name
            override suspend fun fetch() = TestFact(value)
        }
        val primarySource = source("primary", primary)
        val secondarySource = source("secondary", secondary)
        return E2eContext(
            dispatcher = object : Dispatcher {
                override suspend fun dispatch(action: String, payload: JsonObject) =
                    DispatchResult(true, null, null, null)
            },
            factSources = mapOf(
                primarySource.name to primarySource,
                secondarySource.name to secondarySource,
            ),
            eventsFetcher = object : EventsFetcher {
                override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) =
                    emptyList<E2eEvent>()
            },
        )
    }

    private fun context(vararg sources: FactSource<*>): E2eContext =
        E2eContext(
            dispatcher = object : Dispatcher {
                override suspend fun dispatch(action: String, payload: JsonObject) =
                    DispatchResult(true, null, null, null)
            },
            factSources = sources.associateBy { it.name },
            eventsFetcher = object : EventsFetcher {
                override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) =
                    emptyList<E2eEvent>()
            },
        )
}
