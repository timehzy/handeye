package dev.handeye.orchestrator.dispatcher

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import com.sun.net.httpserver.HttpHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpDispatcherTest {

    private lateinit var server: MockDebugServer

    @BeforeTest
    fun setUp() {
        server = MockDebugServer()
        server.setIntentHandler(HttpHandler { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = Json.parseToJsonElement(body).jsonObject
            server.receivedIntents += "/intent" to json
            val action = json["class"]?.jsonPrimitive?.content ?: ""
            val response = buildJsonObject {
                put("dispatched", true)
                put("consumerTag", action.substringBefore(".").lowercase())
                put("stateSnapshot", buildJsonObject { put("handled", true) })
            }
            MockDebugServer.writeJson(ex, 200, response.toString())
        })
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `HttpDispatcher sends POST to intent with class and args`() = runBlocking {
        val http = OrchestratorHttpClient(server.baseUrl)
        val dispatcher = HttpDispatcher(http)
        val payload = buildJsonObject { put("index", 1) }

        val result = dispatcher.dispatch("AspectRatio.Select", payload)

        assertTrue(result.dispatched)
        assertEquals("aspectratio", result.consumerTag)
        assertNotNull(result.stateSnapshot)
        assertEquals(true, result.stateSnapshot!!["handled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertNull(result.errorMessage)

        assertEquals(1, server.receivedIntents.size)
        val (path, body) = server.receivedIntents.first()
        assertEquals("/intent", path)
        assertEquals("AspectRatio.Select", body["class"]?.jsonPrimitive?.content)
        assertEquals(payload, body["args"]?.jsonObject)
    }

    @Test
    fun `HttpDispatcher returns failure when server responds 500`() = runBlocking {
        server.setIntentHandler(HttpHandler { ex ->
            MockDebugServer.writeJson(ex, 500, """{"error":"no handler installed"}""")
        })
        val dispatcher = HttpDispatcher(OrchestratorHttpClient(server.baseUrl))

        val result = dispatcher.dispatch("AspectRatio.Select", buildJsonObject { })

        assertFalse(result.dispatched)
        assertNull(result.consumerTag)
        assertNull(result.stateSnapshot)
        assertEquals("no response", result.errorMessage)
    }
}
