package dev.handeye.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** JVM 单测：android.net.Uri 在 mockable jar 上不可用，android 相关面走 [BootstrapIntentParser.parseCore] 纯核心。 */
class BootstrapIntentParserTest {
    @Test
    fun `null intent 返回 null`() {
        assertNull(BootstrapIntentParser.parse(null))
    }

    @Test
    fun `非 handeye scheme 返回 null`() {
        assertNull(
            BootstrapIntentParser.parseCore(
                scheme = "https",
                host = "bootstrap",
                query = mapOf("work_path" to "demo"),
                coldStart = true,
            )
        )
    }

    @Test
    fun `host 不是 bootstrap 返回 null`() {
        assertNull(
            BootstrapIntentParser.parseCore(
                scheme = "handeye",
                host = "other",
                query = mapOf("work_path" to "demo"),
                coldStart = true,
            )
        )
    }

    @Test
    fun `多 query 参数全部落入 JsonObject`() {
        val obj = BootstrapIntentParser.parseCore(
            scheme = "handeye",
            host = "bootstrap",
            query = mapOf("work_path" to "demo", "tag" to "e2e"),
            coldStart = true,
        )!!
        assertEquals("demo", obj["work_path"]!!.jsonPrimitive.content)
        assertEquals("e2e", obj["tag"]!!.jsonPrimitive.content)
        assertTrue(obj["_coldStart"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `无参数时只剩 _coldStart`() {
        val obj = BootstrapIntentParser.parseCore(
            scheme = "handeye",
            host = "bootstrap",
            query = emptyMap(),
            coldStart = false,
        )!!
        assertEquals(setOf("_coldStart"), obj.keys)
        assertFalse(obj["_coldStart"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `query 值为 null 时落空串`() {
        val obj = BootstrapIntentParser.parseCore(
            scheme = "handeye",
            host = "bootstrap",
            query = mapOf("flag" to null),
            coldStart = true,
        )!!
        assertEquals("", obj["flag"]!!.jsonPrimitive.content)
    }
}
