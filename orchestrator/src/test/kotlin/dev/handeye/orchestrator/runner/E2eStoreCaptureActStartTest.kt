package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.HttpDispatcher
import dev.handeye.orchestrator.events.HttpEventsFetcher
import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E2eStore.captureActStart` 单测：主动作边界完全由 device event `seq` 表达。
 */
class E2eStoreCaptureActStartTest {

    private lateinit var server: MockDebugServer
    private lateinit var store: E2eStore

    @BeforeTest
    fun setUp() {
        server = MockDebugServer(port = 0)
        server.readiness = true
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

    @Test
    fun `empty baseline captures zero and includes first event`() {
        assertTrue(server.events.isEmpty(), "前置：baseline 必须为空")

        store.captureActStart()
        assertEquals(0L, store.actStartSeq)

        server.pushEvent(buildJsonObject {
            put("t", 0L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        assertEquals(1, store.eventsAfterSeq(store.actStartSeq).size)
    }

    @Test
    fun `non-empty baseline captureActStart excludes last baseline event`() {
        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        store.captureActStart()
        assertEquals(1L, store.actStartSeq)

        server.pushEvent(buildJsonObject {
            put("t", 100L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.pause")
        })

        assertEquals(1, store.eventsAfterSeq(store.actStartSeq).size)
    }

    /**
     * case 3（barrier 语义回归）：given=Play → baseline 里已有 playerCmd/play；act 也
     * dispatch Play。captureActStart 后再等 barrier(playerCmd, play) 时，必须**只**匹配到
     * act 新的那条，不能拿 baseline 里的旧条假 hit。
     */
    @Test
    fun `barrier does not false-hit baseline event when act dispatches same intent`() {
        // given 段派发 Play：留一条 baseline
        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        store.captureActStart()

        val evsBeforeAct = store.eventsAfterSeq(store.actStartSeq)
        assertTrue(
            evsBeforeAct.isEmpty(),
            "captureActStart 后立刻查应为空；实际=$evsBeforeAct",
        )

        // act 触发 Play：新一条 playerCmd/play 在 t=50
        server.pushEvent(buildJsonObject {
            put("t", 50L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        val evsAfterAct = store.eventsAfterSeq(store.actStartSeq)
        assertEquals(1, evsAfterAct.size, "只应包含 act 新写的 t=50 那条")
    }

    @Test
    fun `baseline with single event captures its seq`() {
        server.pushEvent(buildJsonObject {
            put("t", 0L)
            put("kind", "state")
        })

        store.captureActStart()
        assertEquals(1L, store.actStartSeq)

        // 后续 act 事件 t=5
        server.pushEvent(buildJsonObject {
            put("t", 5L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        val evs = store.eventsAfterSeq(store.actStartSeq)
        assertEquals(1, evs.size, "只应包含 t=5 的 act 事件")
    }

    @Test
    fun `same timestamp baseline and act are separated by seq`() {
        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("seq", 1L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.play")
        })

        store.captureActStart()
        assertEquals(1L, store.actStartSeq)

        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("seq", 2L)
            put("kind", "playerCmd")
            put("op", "KMPBizPlayer.pause")
        })

        val events = store.eventsAfterSeq(store.actStartSeq)
        assertEquals(1, events.size)
        assertEquals("KMPBizPlayer.pause", events.single()["op"]?.toString()?.trim('"'))
    }

    @Test
    fun `same kind and name in given does not false-hit act boundary`() {
        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("seq", 1L)
            put("kind", "effect")
            put("name", "Applied")
        })

        store.captureActStart()

        assertTrue(store.eventsAfterSeq(store.actStartSeq).isEmpty())

        server.pushEvent(buildJsonObject {
            put("t", 42L)
            put("seq", 2L)
            put("kind", "effect")
            put("name", "Applied")
        })

        assertEquals(1, store.eventsAfterSeq(store.actStartSeq).size)
    }
}
