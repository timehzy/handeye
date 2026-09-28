package dev.handeye.orchestrator.orchestrator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 4.8：[SnapshotDiff.diff] 核心遍历算法单测。
 *
 * ## 覆盖
 *
 * - 相同 → 空 diff
 * - 顶层字段变化 → CHANGED
 * - 嵌套字段变化 → path `parent.child` 命中
 * - ADDED / REMOVED 双方向
 * - 字符串字段（uiState toString）不同 → CHANGED，不深入
 * - 空对象 vs 空对象 → 空 diff
 * - 深度嵌套多层：`a.b.c.d` path 正确
 * - 数组按叶子对比（不下钻）
 * - 类型不同（object vs primitive）→ CHANGED
 * - 输出按 path 字典序排序
 */
class SnapshotDiffTest {

    @Test
    fun `identical snapshots produce empty diff`() {
        val a = buildJsonObject {
            put("t", 100)
            put("seq", 1)
            put("editModule", buildJsonObject { put("ratio", "16:9") })
        }
        val b = buildJsonObject {
            put("t", 100)
            put("seq", 1)
            put("editModule", buildJsonObject { put("ratio", "16:9") })
        }
        assertEquals(emptyList(), SnapshotDiff.diff(a, b))
    }

    @Test
    fun `top-level scalar change is CHANGED`() {
        val a = buildJsonObject { put("t", 100); put("seq", 1) }
        val b = buildJsonObject { put("t", 999); put("seq", 1) }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("t", e.path)
        assertEquals(SnapshotDiff.DiffKind.CHANGED, e.kind)
        assertEquals("100", e.leftValue)
        assertEquals("999", e.rightValue)
    }

    @Test
    fun `nested field change reports parent dot child path`() {
        val a = buildJsonObject {
            put("editModule", buildJsonObject { put("ratio", "9:16") })
        }
        val b = buildJsonObject {
            put("editModule", buildJsonObject { put("ratio", "16:9") })
        }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("editModule.ratio", e.path)
        assertEquals(SnapshotDiff.DiffKind.CHANGED, e.kind)
        assertEquals("\"9:16\"", e.leftValue)
        assertEquals("\"16:9\"", e.rightValue)
    }

    @Test
    fun `ADDED on right-only key`() {
        val a = buildJsonObject { put("editModule", buildJsonObject { }) }
        val b = buildJsonObject {
            put("editModule", buildJsonObject { put("beauty", true) })
        }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("editModule.beauty", e.path)
        assertEquals(SnapshotDiff.DiffKind.ADDED, e.kind)
        assertEquals(null, e.leftValue)
        assertEquals("true", e.rightValue)
    }

    @Test
    fun `REMOVED on left-only key`() {
        val a = buildJsonObject {
            put("editModule", buildJsonObject { put("beauty", true) })
        }
        val b = buildJsonObject { put("editModule", buildJsonObject { }) }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("editModule.beauty", e.path)
        assertEquals(SnapshotDiff.DiffKind.REMOVED, e.kind)
        assertEquals("true", e.leftValue)
        assertEquals(null, e.rightValue)
    }

    @Test
    fun `uiState string field diff without deep-diving toString`() {
        // uiState 是 data class toString 字符串——同一 JsonPrimitive string，diff 只报 CHANGED，
        // 不会尝试解析 toString 内部结构（未来改 @Serializable 后自动能深入）
        val a = buildJsonObject { put("uiState", "KMPDemoPlayerState(ratio=9:16)") }
        val b = buildJsonObject { put("uiState", "KMPDemoPlayerState(ratio=16:9)") }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("uiState", e.path, "字符串字段不下钻，只报顶层 path")
        assertEquals(SnapshotDiff.DiffKind.CHANGED, e.kind)
    }

    @Test
    fun `two empty objects produce empty diff`() {
        val a: JsonObject = buildJsonObject { }
        val b: JsonObject = buildJsonObject { }
        assertEquals(emptyList(), SnapshotDiff.diff(a, b))
    }

    @Test
    fun `deep nesting resolves full dotted path`() {
        val a = buildJsonObject {
            put(
                "a",
                buildJsonObject {
                    put(
                        "b",
                        buildJsonObject {
                            put("c", buildJsonObject { put("d", 1) })
                        },
                    )
                },
            )
        }
        val b = buildJsonObject {
            put(
                "a",
                buildJsonObject {
                    put(
                        "b",
                        buildJsonObject {
                            put("c", buildJsonObject { put("d", 2) })
                        },
                    )
                },
            )
        }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        assertEquals("a.b.c.d", entries.single().path)
    }

    @Test
    fun `array leaves are compared by equality without index-level diff`() {
        val a = buildJsonObject {
            put("tags", buildJsonArray { add(JsonPrimitive("smoke")); add(JsonPrimitive("playback")) })
        }
        val b = buildJsonObject {
            put("tags", buildJsonArray { add(JsonPrimitive("smoke")); add(JsonPrimitive("filter")) })
        }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("tags", e.path, "数组不下钻，路径停在数组字段本身")
        assertEquals(SnapshotDiff.DiffKind.CHANGED, e.kind)
    }

    @Test
    fun `object vs primitive on same key is CHANGED at the key`() {
        // 类型漂移场景：一边是 object 一边是 primitive—— diff 不再递归，直接 CHANGED
        val a = buildJsonObject {
            put("editModule", buildJsonObject { put("ratio", "16:9") })
        }
        val b = buildJsonObject { put("editModule", "unavailable") }
        val entries = SnapshotDiff.diff(a, b)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("editModule", e.path)
        assertEquals(SnapshotDiff.DiffKind.CHANGED, e.kind)
    }

    @Test
    fun `entries are sorted by path lexicographically`() {
        val a = buildJsonObject {
            put("z", 1)
            put("a", 1)
            put("m", 1)
        }
        val b = buildJsonObject {
            put("z", 2)
            put("a", 2)
            put("m", 2)
        }
        val paths = SnapshotDiff.diff(a, b).map { it.path }
        assertEquals(listOf("a", "m", "z"), paths)
    }

    @Test
    fun `multi-kind mixed changes at multiple depths`() {
        // 综合 case：CHANGED 顶层 + ADDED 嵌套 + REMOVED 嵌套 + UNCHANGED 段被忽略
        val a = buildJsonObject {
            put("t", 100)
            put("seq", 1)
            put("editModule", buildJsonObject { put("ratio", "9:16"); put("beauty", true) })
            put("keep", "same")
        }
        val b = buildJsonObject {
            put("t", 200)
            put("seq", 2)
            put("editModule", buildJsonObject { put("ratio", "16:9"); put("pip", true) })
            put("keep", "same")
        }
        val entries = SnapshotDiff.diff(a, b)
        val pathKinds = entries.map { it.path to it.kind }.toSet()
        assertTrue(("t" to SnapshotDiff.DiffKind.CHANGED) in pathKinds)
        assertTrue(("seq" to SnapshotDiff.DiffKind.CHANGED) in pathKinds)
        assertTrue(("editModule.ratio" to SnapshotDiff.DiffKind.CHANGED) in pathKinds)
        assertTrue(("editModule.beauty" to SnapshotDiff.DiffKind.REMOVED) in pathKinds)
        assertTrue(("editModule.pip" to SnapshotDiff.DiffKind.ADDED) in pathKinds)
        // keep 字段应被忽略
        assertTrue(entries.none { it.path == "keep" }, "未变字段不应出现在 diff 中")
    }
}
