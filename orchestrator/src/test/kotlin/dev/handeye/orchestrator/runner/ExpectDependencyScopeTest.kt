package dev.handeye.orchestrator.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpectDependencyScopeTest {

    private fun effectEvent(name: String): JsonObject = buildJsonObject {
        put("kind", "effect")
        put("name", name)
    }

    private fun editModuleCmdEvent(op: String, args: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject {
            put("kind", "editModuleCmd")
            put("op", op)
            put("args", args)
        }

    @Test
    fun `expectEventCount 次数相等通过`() {
        val scope = ExpectDependencyScope()
        scope.expectEventCount(
            kind = "effect",
            name = "ToastSpeedHighSpeedHint",
            count = 2,
        )
        val events = listOf(
            effectEvent("ToastSpeedHighSpeedHint"),
            effectEvent("CloseSubPanel"),
            effectEvent("ToastSpeedHighSpeedHint"),
        )
        assertTrue(scope.checks.single().predicate(events))
    }

    @Test
    fun `expectEventCount 次数不等失败`() {
        val scope = ExpectDependencyScope()
        scope.expectEventCount(kind = "effect", name = "CloseSubPanel", count = 1)
        val events = listOf(effectEvent("CloseSubPanel"), effectEvent("CloseSubPanel"))
        assertFalse(scope.checks.single().predicate(events))
    }

    @Test
    fun `expectEventCount 与 expectNoEvent`() {
        val scope = ExpectDependencyScope()
        scope.expectEventCount(
            kind = "editModuleCmd",
            name = "stashProject",
            nameKey = "op",
            count = 1,
        )
        scope.expectNoEvent(
            kind = "editModuleCmd",
            name = "setProjectSpeedSections",
            nameKey = "op",
        )
        val events = listOf(editModuleCmdEvent("stashProject"))
        assertTrue(scope.checks[0].predicate(events))
        assertTrue(scope.checks[1].predicate(events))
        val dirty = events + editModuleCmdEvent("setProjectSpeedSections")
        assertFalse(scope.checks[1].predicate(dirty))
    }

    @Test
    fun `expectEvent applies matcher after kind and name`() {
        val scope = ExpectDependencyScope()
        scope.expectEvent(kind = "editModuleCmd", name = "stashProject", nameKey = "op") {
            this["args"]?.toString()?.contains("dirty") == true
        }

        assertFalse(scope.checks.single().predicate(listOf(editModuleCmdEvent("stashProject"))))
        assertTrue(
            scope.checks.single().predicate(
                listOf(editModuleCmdEvent("stashProject", buildJsonObject { put("dirty", true) })),
            ),
        )
    }

    @Test
    fun `expectNoMatchingEvent rejects any business-defined match`() {
        val scope = ExpectDependencyScope()
        scope.expectNoMatchingEvent(
            kind = "playerCmd",
            describe = "op contains reload",
        ) { event ->
            event["op"]?.toString()?.contains("reload") == true
        }

        val clean = buildJsonObject {
            put("kind", "playerCmd")
            put("op", "play")
        }
        val dirty = buildJsonObject {
            put("kind", "playerCmd")
            put("op", "reloadSource")
        }
        assertTrue(scope.checks.single().predicate(listOf(clean)))
        assertFalse(scope.checks.single().predicate(listOf(clean, dirty)))
    }
}
