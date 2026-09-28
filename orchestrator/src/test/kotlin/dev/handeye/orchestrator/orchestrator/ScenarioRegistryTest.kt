package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.runner.ScenarioResult
import dev.handeye.orchestrator.runner.SoftAssertionReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DefaultScenarioRegistry] 单元测试 —— 覆盖注册 / 注销 / 查询 / tag 过滤保序。
 */
class ScenarioRegistryTest {

    private val meta = ScenarioMeta(
        name = "test",
        tags = setOf("smoke"),
        page = HostPage.DEMO,
        run = { _: String ->
            ScenarioResult(
                name = "test",
                pass = true,
                reporter = SoftAssertionReporter(),
                elapsedMs = 0L,
            )
        },
    )

    @Test
    fun `DefaultScenarioRegistry register unregister findByName`() {
        val r = DefaultScenarioRegistry()
        assertNull(r.findByName("test"))
        r.register(meta)
        assertEquals(meta, r.findByName("test"))
        r.unregister("test")
        assertNull(r.findByName("test"))
    }

    @Test
    fun `DefaultScenarioRegistry filterByTag preserves order`() {
        val r = DefaultScenarioRegistry()
        r.register(
            meta.copy(name = "a"),
            meta.copy(name = "b", tags = setOf("other")),
            meta.copy(name = "c"),
        )
        val tagged = r.filterByTag("smoke")
        assertEquals(listOf("a", "c"), tagged.map { it.name })
    }
}
