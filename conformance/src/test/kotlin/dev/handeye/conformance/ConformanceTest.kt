package dev.handeye.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValidatorTest {

    private val goldenDir = File(System.getProperty("golden.dir"))

    @Test
    fun `valid envelope passes`() {
        val violations = SchemaValidator.validate(File(goldenDir, "envelope.jsonl").readText())
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `demo feed golden passes`() {
        val violations = SchemaValidator.validate(File(goldenDir, "demo_feed.jsonl").readText())
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `non-monotonic seq fails`() {
        val text = """
            {"seq":2,"t":0,"kind":"a","name":"b"}
            {"seq":1,"t":1,"kind":"a","name":"b"}
        """.trimIndent()
        assertTrue(SchemaValidator.validate(text).any { "seq" in it.message })
    }

    @Test
    fun `blank kind fails`() {
        val text = """{"seq":1,"t":0,"kind":"","name":"b"}"""
        assertTrue(SchemaValidator.validate(text).any { "kind" in it.message })
    }

    @Test
    fun `negative t fails`() {
        val text = """{"seq":1,"t":-5,"kind":"a","name":"b"}"""
        assertTrue(SchemaValidator.validate(text).any { "t 为负数" in it.message })
    }

    @Test
    fun `unparseable line fails`() {
        val text = "not-json"
        assertTrue(SchemaValidator.validate(text).any { "解析失败" in it.message })
    }
}

class GoldenDifferTest {

    @Test
    fun `identical modulo t and seq passes`() {
        val actual = """
            {"seq":80,"t":7,"kind":"state","name":"FeedUiState","payload":{"listSize":20}}
            {"seq":81,"t":9,"kind":"dbWrite","name":"feed","payload":{"rowCount":20}}
        """.trimIndent()
        val golden = """
            {"seq":1,"t":0,"kind":"state","name":"FeedUiState","payload":{"listSize":20}}
            {"seq":2,"t":1,"kind":"dbWrite","name":"feed","payload":{"rowCount":20}}
        """.trimIndent()
        assertEquals(emptyList(), GoldenDiffer.diff(actual, golden))
    }

    @Test
    fun `payload key order is ignored`() {
        val actual = """{"seq":1,"t":0,"kind":"a","name":"b","payload":{"x":1,"y":2}}"""
        val golden = """{"seq":1,"t":0,"kind":"a","name":"b","payload":{"y":2,"x":1}}"""
        assertEquals(emptyList(), GoldenDiffer.diff(actual, golden))
    }

    @Test
    fun `kind mismatch is reported`() {
        val actual = """{"seq":1,"t":0,"kind":"a","name":"b"}"""
        val golden = """{"seq":1,"t":0,"kind":"c","name":"b"}"""
        assertEquals(1, GoldenDiffer.diff(actual, golden).size)
    }

    @Test
    fun `extra event is reported`() {
        val actual = """
            {"seq":1,"t":0,"kind":"a","name":"b"}
            {"seq":2,"t":1,"kind":"a","name":"b"}
        """.trimIndent()
        val golden = """{"seq":1,"t":0,"kind":"a","name":"b"}"""
        assertTrue(GoldenDiffer.diff(actual, golden).any { "多出" in it })
    }
}
