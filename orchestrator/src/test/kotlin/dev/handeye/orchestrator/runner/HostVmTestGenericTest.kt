package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * PR1 Task9 新增 API 的编译期 / 内存行为单测。
 *
 * 不依赖运行中的应用，只验证 DSL 收集行为、[ExpectDependencyScope] 注册机制等价性、
 * 以及 [hostVmTest] 泛型重载的编译期存在性。
 */
class HostVmTestGenericTest {

    private data class TestFact(val count: Int)

    @Test
    fun `expectSource collects typed fact transformer into internal map`() {
        val scope = ExpectFactScope().apply {
            expectSource<TestFact>("primary") { copy(count = 1) }
            consistency("primary.count == 1") { facts ->
                (facts["primary"] as? TestFact)?.count == 1
            }
        }

        assertEquals(1, scope.sourceExpectations.size)

        val input = TestFact(count = 0)
        val output = scope.sourceExpectations["primary"]?.transform?.invoke(input) as? TestFact
        assertEquals(1, output?.count)

        assertEquals(1, scope.sourceConsistencyChecks.size)
        assertTrue(
            scope.sourceConsistencyChecks.single().predicate(mapOf("primary" to TestFact(count = 1))),
        )
    }

    @Test
    fun `expectEvent is wired to generic check registry`() {
        val generic = ExpectDependencyScope().apply {
            expectEvent("playerCmd", "X", "op")
        }

        assertEquals(1, generic.checks.size)

        val matching = listOf(
            buildJsonObject {
                put("kind", "playerCmd")
                put("op", "X")
            },
        )
        assertTrue(generic.checks.single().predicate(matching))

        val missing = listOf(
            buildJsonObject {
                put("kind", "playerCmd")
                put("op", "Y")
            },
        )
        assertFalse(generic.checks.single().predicate(missing))
    }

    @Test
    fun `generic hostVmTest overload exists without invoking TODO body`() {
        val ref: (String, E2eContext, ScenarioScope<E2eContext>.() -> Unit, Duration?) -> ScenarioResult =
            { name, ctx, block, timeout -> hostVmTest<E2eContext>(name, ctx, block, timeout) }
        assertNotNull(ref)
    }
}
