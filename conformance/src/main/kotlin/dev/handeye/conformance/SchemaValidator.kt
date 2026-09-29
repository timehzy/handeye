package dev.handeye.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 事件 envelope 结构校验（protocol/spec.md v1）：
 * 每行 JSON 可解析为对象；seq 为整数且严格单调递增；t 为整数、单调不减、非负；
 * kind / name 为非空字符串；payload 缺省或必须是对象。
 *
 * @return 违规列表，空表示通过。行号从 1 起。
 */
object SchemaValidator {

    data class Violation(val line: Int, val message: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun validate(text: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        var prevSeq: Long? = null
        var prevT: Long? = null

        text.lineSequence()
            .map { it.trim() }
            .forEachIndexed { index, line ->
                val lineNo = index + 1
                if (line.isEmpty()) return@forEachIndexed

                val obj = runCatching { json.parseToJsonElement(line).jsonObject }
                    .getOrElse {
                        violations += Violation(lineNo, "JSON 解析失败: ${it.message}")
                        return@forEachIndexed
                    }

                val seq = obj.longField("seq")
                when {
                    seq == null -> violations += Violation(lineNo, "缺少 seq 或类型不是整数")
                    prevSeq != null && seq <= prevSeq ->
                        violations += Violation(lineNo, "seq 未严格递增: $seq <= $prevSeq")
                }
                if (seq != null) prevSeq = seq

                val t = obj.longField("t")
                when {
                    t == null -> violations += Violation(lineNo, "缺少 t 或类型不是整数")
                    t < 0 -> violations += Violation(lineNo, "t 为负数: $t")
                    prevT != null && t < prevT -> violations += Violation(lineNo, "t 单调递减: $t < $prevT")
                }
                if (t != null) prevT = t

                if (obj.stringField("kind").isNullOrBlank()) {
                    violations += Violation(lineNo, "kind 缺失或为空")
                }
                if (obj.stringField("name").isNullOrBlank()) {
                    violations += Violation(lineNo, "name 缺失或为空")
                }
                if ("payload" in obj && obj["payload"] !is JsonObject) {
                    violations += Violation(lineNo, "payload 必须是 JSON 对象")
                }
            }
        return violations
    }

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() }?.longOrNull

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
