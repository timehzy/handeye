package dev.handeye.orchestrator.dispatcher

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 基于 HTTP POST 的 [Dispatcher] 默认实现。
 *
 * 请求体格式：
 * ```json
 * {"class":"<action>","args":<payload>}
 * ```
 * 响应体按设备端 `/intent` 契约解析 `dispatched` / `consumerTag` / `stateSnapshot` /
 * `errorMessage` 字段。`stateSnapshot` 兼容 JsonObject 与 JSON 字符串两种返回形态。
 *
 * @param httpClient 已配置好 baseUrl 的 [OrchestratorHttpClient]
 * @param endpointPath POST 目标路径，默认 `/intent`
 */
class HttpDispatcher(
    private val httpClient: OrchestratorHttpClient,
    private val endpointPath: String = "/intent",
    private val controlEndpointPath: String = "/control",
) : Dispatcher {

    override suspend fun dispatch(action: String, payload: JsonObject): DispatchResult {
        val body = buildJsonObject {
            put("class", JsonPrimitive(action))
            put("args", payload)
        }
        val response = httpClient.postJson(endpointPath, body)
            ?: return DispatchResult(
                dispatched = false,
                consumerTag = null,
                stateSnapshot = null,
                errorMessage = "no response",
            )
        return DispatchResult(
            dispatched = response["dispatched"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            consumerTag = response["consumerTag"]?.jsonPrimitive?.contentOrNull,
            stateSnapshot = parseStateSnapshot(response["stateSnapshot"]),
            errorMessage = response["errorMessage"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseStateSnapshot(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> runCatching {
            Json.parseToJsonElement(element.content).jsonObject
        }.getOrNull()
        else -> null
    }

    /**
     * 自由格式控制面请求：以 [path] 为动作名、[payload] 为 args POST 到通用控制端点。
     *
     * 响应按控制面通用契约解析：`ok`/`driven` 任一为 true 视作消费成功，`error` 落入
     * [DispatchResult.errorMessage]。
     */
    override suspend fun dispatchControl(path: String, payload: JsonObject): DispatchResult {
        val body = buildJsonObject {
            put("name", JsonPrimitive(path))
            put("args", payload)
        }
        val response = httpClient.postJson(controlEndpointPath, body)
            ?: return DispatchResult(
                dispatched = false,
                consumerTag = null,
                stateSnapshot = null,
                errorMessage = "no response",
            )
        val accepted = response["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true ||
            response["driven"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        return DispatchResult(
            dispatched = accepted,
            consumerTag = null,
            stateSnapshot = null,
            errorMessage = response["error"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
