package dev.handeye.orchestrator.runner

import kotlin.time.Duration

/**
 * 通用 Fact 断言 scope。
 *
 * host 业务 module 通过 [expectSource] 登记 source-name + typed transformer；framework 只比较
 * 当前 Fact 与 transformer 生成的期望 Fact，不解释字段和 source 名。
 */
class ExpectFactScope {

    class SourceExpectation(
        val transform: (Any?) -> Any?,
        val timeout: Duration?,
    )

    class SourceConsistencyCheck(
        val describe: String,
        val predicate: (Map<String, Any?>) -> Boolean,
    )

    @PublishedApi
    internal val sourceExpectations: MutableMap<String, SourceExpectation> = mutableMapOf()

    internal val sourceConsistencyChecks: MutableList<SourceConsistencyCheck> = mutableListOf()

    inline fun <reified T : Any> expectSource(
        sourceName: String,
        timeout: Duration? = null,
        noinline block: T.() -> T,
    ) {
        sourceExpectations[sourceName] = SourceExpectation(
            transform = { current -> (current as T).block() },
            timeout = timeout,
        )
    }

    fun consistency(describe: String, predicate: (Map<String, Any?>) -> Boolean) {
        sourceConsistencyChecks += SourceConsistencyCheck(describe, predicate)
    }
}
