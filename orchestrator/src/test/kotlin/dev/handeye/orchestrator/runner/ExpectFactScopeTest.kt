package dev.handeye.orchestrator.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpectFactScopeTest {

    private data class TestFact(val count: Int)

    @Test
    fun `consistency check 登记自定义谓词并随 scope 收集`() {
        val scope = ExpectFactScope().apply {
            consistency("primary.count == 1") { facts ->
                (facts["primary"] as? TestFact)?.count == 1
            }
        }
        val check = scope.sourceConsistencyChecks.single()
        assertEquals("primary.count == 1", check.describe)
        assertTrue(check.predicate(mapOf("primary" to TestFact(1))))
        assertFalse(check.predicate(mapOf("primary" to TestFact(2))))
    }
}
