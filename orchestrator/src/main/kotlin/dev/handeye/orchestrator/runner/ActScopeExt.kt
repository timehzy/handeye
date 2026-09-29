package dev.handeye.orchestrator.runner

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 轮询 Fact 直到 [selector] 取出非 null 值，返回该值；selector 返回 null 表示值未就绪、继续等待。
 *
 * 适用于「等某个 Fact 字段就绪后用它驱动下一步动作」的场景（如先读 section id 再 dispatch），
 * 调用点无需手写外层捕获变量。超时语义与 [ActScope.awaitFact] 一致。
 */
suspend fun <T : Any> ActScope.awaitFactValue(
    timeout: Duration = 5.seconds,
    selector: (Map<String, Any?>) -> T?,
): T {
    var captured: T? = null
    awaitFact(timeout) { facts ->
        selector(facts)?.also { captured = it } != null
    }
    return checkNotNull(captured)
}
