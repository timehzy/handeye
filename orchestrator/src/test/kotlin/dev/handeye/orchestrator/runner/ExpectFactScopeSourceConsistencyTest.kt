package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.factsource.FactSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ExpectFactScopeSourceConsistencyTest {

    private data class TestFact(val value: Int)

    @Test
    fun `consistency registers a source fact map predicate`() {
        val scope = ExpectFactScope().apply {
            consistency("all sources agree") { facts ->
                facts["first"] == facts["second"]
            }
        }

        val check = assertEquals(1, scope.sourceConsistencyChecks.size).let {
            scope.sourceConsistencyChecks.single()
        }
        assertEquals("all sources agree", check.describe)
        assertTrue(check.predicate(mapOf("first" to 2, "second" to 2)))
    }

    @Test
    fun `source consistency fetches actual facts and reports predicate failure`() = runBlocking {
        val source = object : FactSource<TestFact> {
            override val name = "primary"
            override suspend fun fetch() = TestFact(1)
        }
        val store = E2eStore(
            E2eContext(
                dispatcher = object : Dispatcher {
                    override suspend fun dispatch(action: String, payload: JsonObject) =
                        DispatchResult(true, null, null, null)
                },
                factSources = mapOf(source.name to source),
                eventsFetcher = object : EventsFetcher {
                    override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) =
                        emptyList<E2eEvent>()
                },
            ),
        )
        val scope = ExpectFactScope().apply {
            expectSource<TestFact>("primary") { this }
            consistency("source consistency failure") { false }
        }

        runSourceConsistencyChecks(store, scope)

        assertTrue(store.reporter.hasFailure())
        assertTrue(
            store.reporter.allSteps().any { step ->
                step.label == "source consistency failure" && !step.pass &&
                    step.failReason?.contains("primary=TestFact") == true
            },
        )
    }
}
