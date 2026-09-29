package dev.handeye.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * golden 对拍：把两组 jsonl 各自投影成 (kind, name, payload) 序列——
 * 忽略 t（设备相关）与 seq（device 端跨 reset 不回退，绝对值无意义），
 * payload 按键名排序做规范化比较。序列逐位对比，产出可读的 diff 列表。
 *
 * 忽略 seq 的同时也天然容忍「同一事件序列在两边 seq 整体偏移」的情况。
 */
object GoldenDiffer {

    private val json = Json { ignoreUnknownKeys = true }

    fun diff(actualText: String, goldenText: String): List<String> {
        val actual = project(actualText)
        val golden = project(goldenText)
        val diffs = mutableListOf<String>()

        val shared = minOf(actual.size, golden.size)
        for (i in 0 until shared) {
            if (actual[i] != golden[i]) {
                diffs += "第 ${i + 1} 条事件不一致:\n  actual: ${actual[i]}\n  golden: ${golden[i]}"
            }
        }
        if (actual.size > golden.size) {
            diffs += "actual 多出 ${actual.size - golden.size} 条事件: ${actual.subList(shared, actual.size)}"
        }
        if (golden.size > actual.size) {
            diffs += "golden 多出 ${golden.size - actual.size} 条事件: ${golden.subList(shared, golden.size)}"
        }
        return diffs
    }

    /** 解析失败或结构非法的行会被跳过——结构问题归 SchemaValidator 管，differ 只比对形状。 */
    private fun project(text: String): List<Triple<String, String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val kind = obj["kind"].asString()
                    val name = obj["name"].asString()
                    Triple(kind, name, obj["payload"].canonical())
                }.getOrNull()
            }
            .toList()

    private fun JsonElement?.asString(): String =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "<missing>"

    /** 按键名排序后的紧凑 JSON —— 键序不同但内容相同的 payload 视为相等。 */
    private fun JsonElement?.canonical(): String {
        if (this == null) return "null"
        if (this is JsonObject) {
            val inner = entries.sortedBy { it.key }.joinToString(",") { "${it.key.canonical()}:${it.value.canonical()}" }
            return "{$inner}"
        }
        if (this is kotlinx.serialization.json.JsonArray) {
            return joinToString(",", prefix = "[", postfix = "]") { it.canonical() }
        }
        return toString()
    }

    private fun String.canonical(): String = kotlinx.serialization.json.JsonPrimitive(this).toString()
}
