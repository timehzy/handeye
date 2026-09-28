package dev.handeye.orchestrator.events

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import dev.handeye.orchestrator.orchestrator.withEventsTailer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR1 Task 7 [EventsTailer] 单测 —— 验证：
 *
 * 1. [pollLatest] 通过 [EventsFetcher] 拉事件并推进 `currentSeq`。
 * 2. 不感知具体 kind，不再做 kind-specific 格式化。
 * 3. [withEventsTailer]（orchestrator 调用方）仍能在场景 block 期间后台打印事件，
 *    但输出改为通用格式 `kind=<kind> raw=<json>`。
 */
class EventsTailerTest {

    private class StubFetcher : EventsFetcher {
        val responses = mutableListOf<List<E2eEvent>>()
        var lastSeq: Long? = null
        var lastLimit: Int? = null

        override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int): List<E2eEvent> {
            lastSeq = seq
            lastLimit = limit
            return if (responses.isNotEmpty()) responses.removeFirst() else emptyList()
        }
    }

    private fun event(seq: Long, kind: String, name: String? = null) = E2eEvent(
        seq = seq,
        timeMs = seq * 10,
        kind = kind,
        raw = buildJsonObject {
            put("seq", seq)
            put("t", seq * 10)
            put("kind", kind)
            if (name != null) put("name", name)
        },
    )

    // ---- pollLatest：拉取 & afterSeq 递进 ----

    @Test
    fun `pollLatest returns events and advances currentSeq`() = runBlocking {
        val fetcher = StubFetcher()
        fetcher.responses.add(listOf(event(1, "intent", "A"), event(3, "state")))

        val tailer = EventsTailer(fetcher)
        val events = tailer.pollLatest()

        assertEquals(2, events.size)
        assertEquals(1L, events[0].seq)
        assertEquals(3L, events[1].seq)
        assertEquals(0L, fetcher.lastSeq)
        assertEquals(1000, fetcher.lastLimit)
    }

    @Test
    fun `pollLatest passes currentSeq to next fetch`() = runBlocking {
        val fetcher = StubFetcher()
        fetcher.responses.add(listOf(event(5, "effect")))
        fetcher.responses.add(emptyList())

        val tailer = EventsTailer(fetcher)
        tailer.pollLatest()
        assertEquals(0L, fetcher.lastSeq)

        tailer.pollLatest()
        assertEquals(5L, fetcher.lastSeq)
    }

    @Test
    fun `pollLatest empty response keeps currentSeq`() = runBlocking {
        val fetcher = StubFetcher()
        fetcher.responses.add(emptyList())

        val tailer = EventsTailer(fetcher, startSeq = 42L)
        val events = tailer.pollLatest()

        assertEquals(0, events.size)
        assertEquals(42L, fetcher.lastSeq)
    }

    @Test
    fun `pollLatest honors custom limit`() = runBlocking {
        val fetcher = StubFetcher()
        fetcher.responses.add(emptyList())

        val tailer = EventsTailer(fetcher)
        tailer.pollLatest(limit = 50)

        assertEquals(50, fetcher.lastLimit)
    }

    @Test
    fun `pollLatest startSeq is passed on first fetch`() = runBlocking {
        val fetcher = StubFetcher()
        fetcher.responses.add(listOf(event(10, "intent")))

        val tailer = EventsTailer(fetcher, startSeq = 7L)
        tailer.pollLatest()

        assertEquals(7L, fetcher.lastSeq)
    }

    // ---- withEventsTailer helper：enabled=false no-op / enabled=true launch 后停 ----

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

    private fun serverEvent(
        seq: Long,
        t: Long,
        kind: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("seq", seq)
        put("t", t)
        put("kind", kind)
        block()
    }

    @Test
    fun `withEventsTailer enabled=false 直通 block 不起协程`() {
        var blockRan = false
        withEventsTailer(server.baseUrl, enabled = false) { blockRan = true }
        assertTrue(blockRan)
    }

    @Test
    fun `withEventsTailer enabled=true 期间抓 events block 结束后停`() {
        server.events += serverEvent(seq = 1, t = 1, kind = "intent") {
            put("name", "Ping")
        }
        // 用 System.out 打不好断言；改成用 stdout 重定向捕获 println
        val stdout = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(stdout))
        try {
            withEventsTailer(server.baseUrl, enabled = true) {
                // 给 tailer 起来 + 抓一次的时间
                Thread.sleep(500)
            }
        } finally {
            System.setOut(original)
        }
        val s = stdout.toString(Charsets.UTF_8)
        assertTrue(s.contains("tail-events 已开启"), "应打 tail 开启提示: $s")
        assertTrue(s.contains("[tail seq=1 t=1] kind=intent"), "block 期间应打出通用格式 event: $s")
        assertTrue(s.contains("\"name\":\"Ping\""), "raw JSON 应包含 name 字段: $s")
    }

    @Test
    fun `withEventsTailer enabled=true 输出不再含旧 kind 前缀`() {
        server.events += listOf(
            serverEvent(seq = 1, t = 1, kind = "intent") { put("name", "A") },
            serverEvent(seq = 2, t = 2, kind = "playerCmd") { put("op", "B") },
        )
        val stdout = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(stdout))
        try {
            withEventsTailer(server.baseUrl, enabled = true) {
                Thread.sleep(500)
            }
        } finally {
            System.setOut(original)
        }
        val s = stdout.toString(Charsets.UTF_8)
        assertTrue(s.contains("kind=intent"), s)
        assertTrue(s.contains("kind=playerCmd"), s)
        assertTrue(!s.contains("INTENT>"), "旧 INTENT> 前缀应被删除: $s")
        assertTrue(!s.contains("PCMD>"), "旧 PCMD> 前缀应被删除: $s")
    }
}
