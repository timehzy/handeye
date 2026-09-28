package dev.handeye.orchestrator.dispatcher

import kotlinx.serialization.json.JsonObject

/**
 * 将 e2e action 分派到被测应用的入口抽象。
 *
 * 实现负责把 `action` 与 `payload` 序列化成设备端可识别的格式，并返回是否被消费、消费方
 * tag、最新 state snapshot 与错误信息。
 */
interface Dispatcher {
    suspend fun dispatch(action: String, payload: JsonObject): DispatchResult

    /**
     * 向设备端通用控制面分发自由格式动作（非 Host Intent 通道）。
     *
     * [path] 是控制面动作名（如 `timeline/range-session`），[payload] 作为 args 原样转发；
     * framework 不解释两者语义。默认实现返回不支持错误，未接入控制面的实现无需覆写。
     */
    suspend fun dispatchControl(path: String, payload: JsonObject): DispatchResult =
        DispatchResult(
            dispatched = false,
            consumerTag = null,
            stateSnapshot = null,
            errorMessage = "control dispatch unsupported: $path",
        )
}

/**
 * @param dispatched 是否被成功消费
 * @param consumerTag 消费方标识（如 "aspectRatio"），未消费时为 null
 * @param stateSnapshot 消费后服务端返回的最新状态快照，未返回时为 null
 * @param errorMessage 分发失败时的可读错误，成功时为 null
 */
data class DispatchResult(
    val dispatched: Boolean,
    val consumerTag: String?,
    val stateSnapshot: JsonObject?,
    val errorMessage: String?,
)
