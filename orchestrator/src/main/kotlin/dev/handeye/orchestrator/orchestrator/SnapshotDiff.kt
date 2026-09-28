package dev.handeye.orchestrator.orchestrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Task 4.8：Snapshot diff 核心算法。
 *
 * 输入两份 snapshot JsonObject（由 [SnapshotDiffCli] 从文件读取），输出**扁平化的**结构化
 * diff 列表——每一项对应一个「路径 + 变更类型 + 左右值」三元组。上层 CLI 拿到列表后按行打印。
 *
 * ## 遍历规则
 *
 * - 深度优先递归 [JsonObject]：`parent.child.leaf` 一路拼 path
 * - [JsonArray] / [JsonPrimitive] / [JsonNull] 一律**当作叶子**——序列化后按等值比较
 * - 数组不做 element-level diff：真实 snapshot 里数组少见，且深入 diff 数组要考虑索引对齐 /
 *   增删元素等语义，YAGNI 不做
 * - `uiState` / `renderParams` 是 data class `toString()` 结果（字符串）——本层看到的就是
 *   JsonPrimitive string，按字符串等值比较即可；未来这两字段改 `@Serializable` delta JSON
 *   后，同一算法自动能深入到内部字段
 *
 * ## 顶层噪声字段（`t` / `seq`）
 *
 * 两次 dump 的 `t` / `seq` 通常一定不同，这里**不做特殊过滤**——diff 会显示 CHANGED，用户
 * 自己 grep 排除。过滤规则很容易踩坑（哪些字段算「噪声」是场景相关的），YAGNI 让用户自决。
 *
 * ## 输出顺序
 *
 * 按 path 字典序排序——单测更好断言，人眼扫描也稳定。
 */
object SnapshotDiff {

    enum class DiffKind { ADDED, REMOVED, CHANGED }

    /**
     * @param path 字段路径。顶层字段就是字段名（`"t"` / `"editModule"`），嵌套用 `.` 分隔
     *   （`"editModule.ratio"`）。数组不下钻，路径止于数组字段名本身
     * @param leftValue a 侧的值——JsonElement 的字符串表示（`JsonPrimitive` 直接原样、object/array
     *   走 `toString`）；ADDED 时为 null
     * @param rightValue b 侧的值——同上；REMOVED 时为 null
     * @param kind 变更类型
     */
    data class DiffEntry(
        val path: String,
        val kind: DiffKind,
        val leftValue: String?,
        val rightValue: String?,
    )

    fun diff(a: JsonObject, b: JsonObject): List<DiffEntry> {
        val acc = mutableListOf<DiffEntry>()
        diffObject(prefix = "", a = a, b = b, acc = acc)
        // 稳定顺序方便单测断言 / 人眼扫描
        return acc.sortedBy { it.path }
    }

    private fun diffObject(prefix: String, a: JsonObject, b: JsonObject, acc: MutableList<DiffEntry>) {
        val keys = LinkedHashSet<String>().apply {
            addAll(a.keys)
            addAll(b.keys)
        }
        for (key in keys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val aVal = a[key]
            val bVal = b[key]
            when {
                aVal == null && bVal != null -> acc += DiffEntry(path, DiffKind.ADDED, null, renderLeaf(bVal))
                bVal == null && aVal != null -> acc += DiffEntry(path, DiffKind.REMOVED, renderLeaf(aVal), null)
                aVal is JsonObject && bVal is JsonObject -> diffObject(path, aVal, bVal, acc)
                aVal != null && bVal != null && aVal != bVal ->
                    acc += DiffEntry(path, DiffKind.CHANGED, renderLeaf(aVal), renderLeaf(bVal))
                // 相等 / 都不存在 —— 不产生条目
            }
        }
    }

    /**
     * 把叶子 JsonElement 渲染成 CLI 可打印的字符串。
     *
     * - [JsonPrimitive] string → 带引号（区分数值和字符串）
     * - [JsonPrimitive] 数字/布尔/null → 原样内容
     * - [JsonNull] → `"null"`
     * - [JsonArray] / [JsonObject]（未下钻的情况，比如一边是 object 一边是 primitive）→ `toString()`
     */
    private fun renderLeaf(el: JsonElement): String = when (el) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (el.isString) "\"${el.content}\"" else el.content
        is JsonArray, is JsonObject -> el.toString()
    }
}
