package dev.handeye.orchestrator.context

import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.EventsFetcher
import dev.handeye.orchestrator.factsource.FactSource
import dev.handeye.orchestrator.runner.ActScope

/**
 * 单次 e2e 运行所需的上下文依赖集合。
 *
 * @param dispatcher 将 action 分派到被测应用的入口
 * @param factSources 按名称索引的事实源，用于校验被测应用状态
 * @param eventsFetcher 从被测应用拉取事件日志的入口
 * @param defaults 默认超时与轮询配置
 * @param healthCheck 可选控制面健康检查；null 时框架跳过健康检查
 * @param eventsReset 可选控制面事件重置；null 时框架不执行重置
 * @param diagnostics 可选目标信息与失败诊断采集器
 * @param baselineReset 可选批跑状态隔离钩子；framework 在 [eventsReset] 之后、
 *   scenario `givenState` 之前自动执行；用于把 host page 全部可变业务状态归位
 *   到已知干净起点（关面板 / 清分段 / seek 播放头 / reset selected clip 等）。
 *   null 时框架不执行 baseline reset。要求实现 **幂等**：连续调用与调用一次等价。
 */
data class E2eContext(
    val dispatcher: Dispatcher,
    val factSources: Map<String, FactSource<*>>,
    val eventsFetcher: EventsFetcher,
    val defaults: E2eDefaults = E2eDefaults(),
    val healthCheck: (suspend () -> Boolean)? = null,
    val eventsReset: (suspend () -> Unit)? = null,
    val diagnostics: DiagnosticContext? = null,
    val baselineReset: (suspend ActScope.() -> Unit)? = null,
)
