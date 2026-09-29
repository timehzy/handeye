package dev.handeye.device

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

class EventRecorderTest {

    private fun newRecorder(dir: okio.Path): EventRecorder =
        EventRecorder("$dir/events.jsonl")

    private fun readLines(dir: okio.Path): List<String> =
        FileSystem.SYSTEM.read("$dir/events.jsonl".toPath()) {
            generateSequence { readUtf8Line() }.toList()
        }

    @Test
    fun `record assigns monotonic seq and writes envelope`() = runTest {
        val dir = createTempDirectory().toString().toPath()
        val recorder = newRecorder(dir)
        recorder.record("intent", "Feed.Refresh")
        recorder.record("cacheWrite", "feed", buildJsonObject {
            put("entryCount", 20)
        })
        recorder.flush()
        val lines = readLines(dir)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"seq\":1"))
        assertTrue(lines[0].contains("\"kind\":\"intent\""))
        assertTrue(lines[1].contains("\"seq\":2"))
        assertTrue(lines[1].contains("\"entryCount\":20"))
        recorder.uninstall()
    }

    @Test
    fun `registerFlow subscribes and records each element`() = runBlocking {
        val dir = createTempDirectory().toString().toPath()
        val recorder = newRecorder(dir)
        val flow = MutableSharedFlow<kotlinx.serialization.json.JsonObject>(extraBufferCapacity = 4)
        recorder.registerFlow("state", "screen") { flow }
        assertTrue(recorder.awaitSubscriptionsReady(2000))
        flow.emit(buildJsonObject { put("screen", "home") })
        // emit 入缓存即返回，collector 异步消费；轮询等落盘再断言
        kotlinx.coroutines.withTimeout(2000) {
            while (true) {
                recorder.flush()
                if (FileSystem.SYSTEM.exists("$dir/events.jsonl".toPath())) break
                kotlinx.coroutines.delay(20)
            }
        }
        val lines = readLines(dir)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"kind\":\"state\""))
        assertTrue(lines[0].contains("\"name\":\"home\""))
        recorder.uninstall()
    }

    @Test
    fun `reset clears file but seq keeps increasing`() = runTest {
        val dir = createTempDirectory().toString().toPath()
        val recorder = newRecorder(dir)
        recorder.record("a", "x")
        recorder.flush()
        recorder.reset()
        recorder.flush()
        recorder.record("b", "y")
        recorder.flush()
        val lines = readLines(dir)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"seq\":2"))
        recorder.uninstall()
    }
}
