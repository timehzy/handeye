package dev.handeye.orchestrator.context

import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.factsource.FactSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class E2eContextTest {

    private val stubDispatcher = object : Dispatcher {
        override suspend fun dispatch(action: String, payload: JsonObject) =
            DispatchResult(dispatched = true, consumerTag = "test", stateSnapshot = null, errorMessage = null)
    }
    private val stubEvents = object : EventsFetcher {
        override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) = emptyList<E2eEvent>()
    }
    private val stubSource = object : FactSource<String> {
        override val name = "stub"
        override suspend fun fetch(): String? = null
    }

    @Test
    fun `E2eContext accepts dispatcher and fetcher and source map`() {
        val ctx = E2eContext(
            dispatcher = stubDispatcher,
            factSources = mapOf("ui" to stubSource),
            eventsFetcher = stubEvents,
        )
        assertNotNull(ctx.dispatcher)
        assertEquals(1, ctx.factSources.size)
        assertNotNull(ctx.eventsFetcher)
    }

    @Test
    fun `E2eDefaults provides sensible defaults`() {
        val defaults = E2eDefaults()
        assertEquals(30.seconds, defaults.overallTimeout)
        assertEquals(3.seconds, defaults.perSourceTimeout)
        assertEquals(100.milliseconds, defaults.pollInterval)
    }
}
