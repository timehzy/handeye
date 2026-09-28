package dev.handeye.orchestrator.runner

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 场景动作 scope。Fact barrier 通过 source-name map 表达，framework 不持有 host 业务类型。
 */
interface ActScope {

    suspend fun dispatch(intentClass: String, args: Map<String, Any?> = emptyMap()): DispatchResult

    /**
     * 向设备端通用控制面分发自由格式动作（非 Host Intent 通道，如区间会话驱动）。
     *
     * [path] 是控制面动作名；framework 不解释语义。未接入控制面的实现默认抛
     * [UnsupportedOperationException]。
     */
    suspend fun dispatchControl(path: String, payload: Map<String, Any?>): DispatchResult =
        throw UnsupportedOperationException("dispatchControl 未接入: $path")

    suspend fun advanceTime(duration: Duration)

    suspend fun awaitEvent(
        kind: String,
        name: String,
        nameKey: String = "name",
        timeout: Duration = 5.seconds,
    )

    suspend fun awaitFact(
        timeout: Duration = 5.seconds,
        predicate: (Map<String, Any?>) -> Boolean,
    )

    suspend fun withStrict(block: suspend ActScope.() -> Unit)

    data class DispatchResult(
        val dispatched: Boolean,
        val consumerTag: String?,
        val stateSnapshotRaw: String,
        val errorMessage: String? = null,
    )
}
