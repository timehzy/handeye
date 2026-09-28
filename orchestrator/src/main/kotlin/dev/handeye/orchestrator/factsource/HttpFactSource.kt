package dev.handeye.orchestrator.factsource

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import kotlinx.serialization.json.JsonObject

class HttpFactSource<T>(
    override val name: String,
    private val httpClient: OrchestratorHttpClient,
    private val endpointPath: String,
    private val projection: (JsonObject) -> T,
) : FactSource<T> {

    override suspend fun fetch(): T? {
        val body = httpClient.getJson(endpointPath) ?: return null
        return projection(body)
    }
}
