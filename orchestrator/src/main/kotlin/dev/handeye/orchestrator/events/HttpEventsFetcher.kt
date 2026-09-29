package dev.handeye.orchestrator.events

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class HttpEventsFetcher(
    private val httpClient: OrchestratorHttpClient,
    private val endpointPath: String = "/events",
) : EventsFetcher {

    override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int): List<E2eEvent> {
        val queryParams = buildMap {
            put("afterSeq", seq.toString())
            put("limit", limit.toString())
            if (kinds != null) put("kinds", kinds.joinToString(","))
        }
        val body = httpClient.getJson(endpointPath, query = queryParams) ?: return emptyList()
        val arr = body["events"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val s = o["seq"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val t = o["t"]?.jsonPrimitive?.longOrNull ?: 0L
            val k = o["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            E2eEvent(seq = s, timeMs = t, kind = k, raw = o)
        }
    }
}
