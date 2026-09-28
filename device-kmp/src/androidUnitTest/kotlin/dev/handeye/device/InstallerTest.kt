package dev.handeye.device

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

class InstallerTest {

    private class FakeServer : HandeyeHttpServer {
        val handlers = mutableMapOf<Pair<String, String>, suspend (HttpRequest) -> HttpResponse>()
        var started = false
        override fun start(port: Int) { started = true }
        override fun stop() { started = false }
        override fun registerHandler(method: String, path: String, handler: suspend (HttpRequest) -> HttpResponse) {
            handlers[method to path] = handler
        }
        suspend fun call(
            method: String,
            path: String,
            query: Map<String, String> = emptyMap(),
            body: String? = null,
        ) = handlers.getValue(method to path)(HttpRequest(query, body))
    }

    private fun newRecorderAndCommands(
        dir: okio.Path,
        onRefresh: () -> Unit = {},
    ): Pair<EventRecorder, CommandRegistry> {
        val recorder = EventRecorder("$dir/events.jsonl")
        val commands = CommandRegistry()
        commands.register("Feed.Refresh") { onRefresh() }
        return recorder to commands
    }

    @Test
    fun `install registers seven endpoints and starts server`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        val (recorder, commands) = newRecorderAndCommands(dir)
        HandeyeInstaller.install(
            filesDir = dir.toString(),
            recorder = recorder,
            providers = emptyList(),
            commands = commands,
            server = server,
            enabled = true,
        )
        assertTrue(server.started)
        assertEquals(7, server.handlers.size)
        assertTrue(server.handlers.containsKey("GET" to "/health"))
        assertTrue(server.handlers.containsKey("POST" to "/cmd"))
        assertTrue(server.handlers.containsKey("GET" to "/source"))
        assertTrue(server.handlers.containsKey("GET" to "/events"))
        assertTrue(server.handlers.containsKey("POST" to "/reset"))
        assertTrue(server.handlers.containsKey("POST" to "/wait-for"))
        assertTrue(server.handlers.containsKey("POST" to "/snapshot"))
        HandeyeInstaller.uninstall()
    }

    @Test
    fun `health reports installed and pid`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        val (recorder, commands) = newRecorderAndCommands(dir)
        HandeyeInstaller.install(dir.toString(), recorder, emptyList(), commands, server, true)
        val response = server.call("GET", "/health")
        assertEquals(200, response.code)
        assertTrue(response.body.contains("\"hookInstalled\":true"), response.body)
        assertTrue(response.body.contains("\"pid\":"), response.body)
        HandeyeInstaller.uninstall()
    }

    @Test
    fun `cmd dispatches registered command`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        var called = false
        val (recorder, commands) = newRecorderAndCommands(dir) { called = true }
        HandeyeInstaller.install(dir.toString(), recorder, emptyList(), commands, server, true)
        val response = server.call("POST", "/cmd", body = """{"key":"Feed.Refresh","args":{}}""")
        assertEquals(200, response.code)
        assertTrue(called)
        HandeyeInstaller.uninstall()
    }

    @Test
    fun `cmd rejects unknown key with 400`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        val (recorder, commands) = newRecorderAndCommands(dir)
        HandeyeInstaller.install(dir.toString(), recorder, emptyList(), commands, server, true)
        val response = server.call("POST", "/cmd", body = """{"key":"Nope","args":{}}""")
        assertEquals(400, response.code)
        HandeyeInstaller.uninstall()
    }

    @Test
    fun `source returns named provider snapshot`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        val (recorder, commands) = newRecorderAndCommands(dir)
        HandeyeInstaller.install(
            dir.toString(), recorder,
            listOf(stateProvider("ui") { buildJsonObject { put("screen", "home") } }),
            commands, server, true,
        )
        val response = server.call("GET", "/source", query = mapOf("name" to "ui"))
        assertEquals(200, response.code)
        assertTrue(response.body.contains("home"), response.body)
        HandeyeInstaller.uninstall()
    }

    @Test
    fun `reinstall with same registry is idempotent`() = runBlocking {
        val server = FakeServer()
        val dir = createTempDirectory().toString().toPath()
        val (recorder, commands) = newRecorderAndCommands(dir)
        val providers = listOf(stateProvider("ui") { buildJsonObject {} })
        val first = HandeyeInstaller.install(dir.toString(), recorder, providers, commands, server, true)
        val second = HandeyeInstaller.install(dir.toString(), recorder, providers, commands, server, true)
        assertTrue(first is InstallResult.Installed)
        assertTrue(second is InstallResult.Installed)
        assertEquals(
            (first as InstallResult.Installed).installId,
            (second as InstallResult.Installed).installId,
        )
        HandeyeInstaller.uninstall()
    }
}
