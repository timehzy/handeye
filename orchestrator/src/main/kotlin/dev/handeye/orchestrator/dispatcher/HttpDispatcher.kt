package dev.handeye.orchestrator.dispatcher

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 基于 HTTP POST 的 [Dispatcher] 默认实现，走协议 v1 的 `/cmd` 端点。
 *
 * 请求体格式：
 * ```json
 * {"key":"<action>","args":<payload>}
 * ```
 * 响应体解析 `accepted` / `error` 字段。协议 v1 的 /cmd 仅 ack 入队，不返回状态快照，
 * 因此 [DispatchResult.stateSnapshot] 恒为 null。
 *
 * @param httpClient 已配置好 baseUrl的 [OrchestratorHttpClient]
 * @param endpointPath POST 目标路径，默认 `/cmd`
 */
class HttpDispatcher(
    private val httpClient: OrchestratorHttpClient,
    private val endpointPath: String = "/cmd",
    private val controlEndpointPath: String = "/control",
) : Dispatcher {

    override suspend fun dispatch(action: String, payload: JsonObject): DispatchResult {
        val body = buildJsonObject {
            put("key", action)
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
            dispatched = response["accepted"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            consumerTag = null,
            stateSnapshot = null,
            errorMessage = response["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * 自由格式控制面请求：以 [path] 为动作名、[payload] 为 args POST 到通用控制端点。
     *
     * 响应按控制面通用契约解析：`ok`/`driven` 任一为 true 视作消费成功，`error` 落入
     * [DispatchResult.errorMessage]。
     */
    override suspend fun dispatchControl(path: String, payload: JsonObject): DispatchResult {
        val body = buildJsonObject {
            put("name", path)
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
