package dev.handeye.orchestrator.events

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpEventsFetcherTest {

    private lateinit var server: MockDebugServer
    private lateinit var http: OrchestratorHttpClient

    @BeforeTest
    fun setUp() {
        server = MockDebugServer()
        server.start()
        http = OrchestratorHttpClient(server.baseUrl, timeoutMs = 1_000L)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `HttpEventsFetcher fetches events after given seq`() = runBlocking {
        server.events += listOf(
            buildJsonObject {
                put("seq", 6L)
                put("t", 100L)
                put("kind", "intent")
                put("name", "X")
            },
            buildJsonObject {
                put("seq", 7L)
                put("t", 101L)
                put("kind", "effect")
                put("name", "Y")
            },
        )

        val fetcher = HttpEventsFetcher(http)
        val events = fetcher.fetchAfter(seq = 5L, kinds = null, limit = 100)

        assertEquals(2, events.size)
        assertEquals(6L, events[0].seq)
        assertEquals(100L, events[0].timeMs)
        assertEquals("intent", events[0].kind)
        assertEquals("X", events[0].stringField("name"))
        assertEquals(7L, events[1].seq)
        assertEquals(101L, events[1].timeMs)
        assertEquals("effect", events[1].kind)
        assertEquals("Y", events[1].stringField("name"))
    }

    @Test
    fun `HttpEventsFetcher filters by kinds`() = runBlocking {
        server.events += listOf(
            buildJsonObject {
                put("seq", 1L)
                put("t", 10L)
                put("kind", "intent")
            },
            buildJsonObject {
                put("seq", 2L)
                put("t", 20L)
                put("kind", "effect")
            },
            buildJsonObject {
                put("seq", 3L)
                put("t", 30L)
                put("kind", "state")
            },
        )

        val fetcher = HttpEventsFetcher(http)
        val events = fetcher.fetchAfter(seq = 0L, kinds = setOf("intent", "state"), limit = 100)

        assertEquals(2, events.size)
        assertEquals(1L, events[0].seq)
        assertEquals(3L, events[1].seq)
    }

    @Test
    fun `HttpEventsFetcher returns empty list when no events match`() = runBlocking {
        val fetcher = HttpEventsFetcher(http)
        val events = fetcher.fetchAfter(seq = 0L, kinds = null, limit = 100)

        assertEquals(0, events.size)
    }

    @Test
    fun `HttpEventsFetcher skips invalid events missing required fields`() = runBlocking {
        server.events += listOf(
            buildJsonObject {
                // missing seq
                put("t", 10L)
                put("kind", "intent")
            },
            buildJsonObject {
                put("seq", 2L)
                put("t", 20L)
                // missing kind
            },
            buildJsonObject {
                put("seq", 3L)
                put("t", 30L)
                put("kind", "effect")
            },
        )

        val fetcher = HttpEventsFetcher(http)
        val events = fetcher.fetchAfter(seq = 0L, kinds = null, limit = 100)

        assertEquals(1, events.size)
        assertEquals(3L, events[0].seq)
        assertEquals("effect", events[0].kind)
    }

    @Test
    fun `E2eEvent stringField returns null for missing key`() {
        val event = E2eEvent(
            seq = 1L,
            timeMs = 10L,
            kind = "intent",
            raw = buildJsonObject { },
        )
        assertNull(event.stringField("missing"))
    }
}
