package dev.handeye.orchestrator.factsource

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.orchestrator.MockDebugServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpFactSourceTest {

    private lateinit var server: MockDebugServer

    @BeforeTest
    fun setUp() {
        server = MockDebugServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `HttpFactSource fetches JSON and applies projection`() {
        server.currentState = buildJsonObject {
            put("selectedRatioIndex", 1)
        }

        val http = OrchestratorHttpClient(server.baseUrl)
        data class UiFact(val selectedRatioIndex: Int? = null)
        val source = HttpFactSource(
            name = "ui",
            httpClient = http,
            endpointPath = "/state",
            projection = { body ->
                UiFact(
                    selectedRatioIndex = body["state"]
                        ?.jsonObject
                        ?.get("selectedRatioIndex")
                        ?.jsonPrimitive
                        ?.intOrNull,
                )
            },
        )
        val result = runBlocking { source.fetch() }
        assertEquals(UiFact(selectedRatioIndex = 1), result)
    }
}
