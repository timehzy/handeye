package dev.handeye.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RegistryTest {

    @Test
    fun `command registry dispatches by key`() {
        val registry = CommandRegistry()
        var received: String? = null
        registry.register("Feed.Refresh") { args ->
            received = args["page"]?.toString()
        }
        registry.dispatch("Feed.Refresh", buildJsonObject { put("page", 1) })
        assertEquals("1", received)
    }

    @Test
    fun `command registry rejects unknown key`() {
        val registry = CommandRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.dispatch("Nope", buildJsonObject {})
        }
    }

    @Test
    fun `registry identity changes with content`() {
        val registry = CommandRegistry()
        val id1 = registry.identity
        registry.register("A") {}
        val id2 = registry.identity
        assertTrue(id1 != id2)
    }

    @Test
    fun `state provider returns named snapshot`() {
        val provider = stateProvider("ui") { buildJsonObject { put("screen", "home") } }
        assertEquals("ui", provider.name)
        val snapshot = provider.snapshot() as kotlinx.serialization.json.JsonObject
        assertEquals("home", snapshot["screen"]?.jsonPrimitive?.content)
    }
}
