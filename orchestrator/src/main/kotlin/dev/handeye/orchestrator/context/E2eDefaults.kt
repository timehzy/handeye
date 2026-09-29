package dev.handeye.orchestrator.context

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * e2e 运行默认配置。
 *
 * @param overallTimeout 整个 scenario 的总超时
 * @param perSourceTimeout 每个 fact source 的查询超时
 * @param pollInterval 轮询间隔
 * @param healthCheckTimeout 健康检查超时
 * @param eventsBatchLimit 单次事件拉取数量上限
 */
data class E2eDefaults(
    val overallTimeout: Duration = 30.seconds,
    val perSourceTimeout: Duration = 3.seconds,
    val pollInterval: Duration = 100.milliseconds,
    val healthCheckTimeout: Duration = 1.seconds,
    val eventsBatchLimit: Int = 1000,
)
