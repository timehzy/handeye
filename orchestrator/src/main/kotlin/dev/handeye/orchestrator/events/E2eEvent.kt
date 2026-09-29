package dev.handeye.orchestrator.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface EventsFetcher {
    suspend fun fetchAfter(seq: Long, kinds: Set<String>? = null, limit: Int = 1000): List<E2eEvent>
}

data class E2eEvent(
    val seq: Long,
    val timeMs: Long,
    val kind: String,
    val raw: JsonObject,
) {
    fun stringField(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
}
