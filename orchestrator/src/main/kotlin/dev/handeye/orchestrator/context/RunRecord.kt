package dev.handeye.orchestrator.context

/**
 * 单次 scenario 运行记录。
 *
 * @param scenarioName scenario 名称
 * @param startedAtMs 开始时间戳（毫秒）
 * @param finishedAtMs 结束时间戳（毫秒）
 * @param passed 是否通过
 * @param failedSoft 软断言失败次数
 * @param artifactsPath 产物目录路径
 */
data class RunRecord(
    val scenarioName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val passed: Boolean,
    val failedSoft: Int = 0,
    val artifactsPath: String? = null,
)
