package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Result of one [handeyeTest] execution. */
data class ScenarioResult(
    val name: String,
    val pass: Boolean,
    val reporter: SoftAssertionReporter,
    val elapsedMs: Long,
)

/**
 * Generic scenario entry point. Baseline and observed facts are source-name maps; host modules own
 * typed accessors and business assertions.
 */
@Suppress("FINAL_UPPER_BOUND")
inline fun <reified Ctx : E2eContext> handeyeTest(
    name: String,
    ctx: Ctx,
    noinline block: ScenarioScope<Ctx>.() -> Unit,
    overallTimeout: Duration? = null,
): ScenarioResult {
    val scope = ScenarioScope(ctx).apply(block)
    return runHostVmTest(
        name = name,
        ctx = ctx,
        givenState = scope.givenStateBlock,
        act = scope.actBlock ?: error("ScenarioScope.act 必须设置"),
        expectFact = scope.expectFactBlock,
        expectDependencies = scope.expectDependenciesBlock,
        strictness = scope.strictnessMode,
        timeout = overallTimeout ?: ctx.defaults.overallTimeout,
    )
}

@PublishedApi
internal fun runHostVmTest(
    name: String,
    ctx: E2eContext,
    givenState: (suspend ActScope.() -> Unit)?,
    act: suspend ActScope.() -> Unit,
    expectFact: (ExpectFactScope.(Map<String, Any?>) -> Unit)?,
    expectDependencies: (ExpectDependencyScope.() -> Unit)?,
    strictness: StrictnessMode,
    timeout: Duration,
): ScenarioResult = runBlocking {
    val mark = TimeSource.Monotonic.markNow()
    val store = E2eStore(ctx)

    try {
        withTimeout(timeout) {
            if (!store.checkHealth()) {
                store.reporter.step(
                    "health_check",
                    pass = false,
                    failReason = "健康检查失败 —— 检查 host page 与测试 hook 是否就绪",
                )
                return@withTimeout
            }
            store.resetEvents()

            val actScope = ActScopeImpl(store, strictness)
            var baselineResetFailed = false
            ctx.baselineReset?.let { reset ->
                try {
                    reset.invoke(actScope)
                    // baselineReset 期间发出的 events 是隔离前置态的动作噪声，
                    // 不应污染 scenario 自己的 dependency 断言窗口——清一次。
                    store.resetEvents()
                } catch (throwable: Throwable) {
                    store.reporter.step(
                        "baseline_reset",
                        pass = false,
                        failReason = "baselineReset 失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    )
                    baselineResetFailed = true
                }
            }

            var givenFailed = baselineResetFailed
            if (!givenFailed && givenState != null) {
                try {
                    givenState.invoke(actScope)
                } catch (throwable: Throwable) {
                    store.reporter.step(
                        "given",
                        pass = false,
                        failReason = "givenState 失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    )
                    givenFailed = true
                }
            }

            if (!givenFailed) {
                val baseline = if (expectFact != null) {
                    try {
                        store.observeSourceFacts()
                    } catch (throwable: Throwable) {
                        store.reporter.step(
                            "baseline_snapshot",
                            pass = false,
                            failReason = "baseline 采集失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
                        )
                        null
                    }
                } else {
                    null
                }

                if (expectFact == null || baseline != null) {
                    store.captureActStart()
                    actScope.act()
                    if (expectFact != null) {
                        runExpectFact(store, expectFact, baseline!!)
                    }
                    if (expectDependencies != null) {
                        runExpectDependencies(store, expectDependencies)
                    }
                }
            }
        }
    } catch (throwable: Throwable) {
        store.reporter.step(
            "scenario",
            pass = false,
            failReason = throwable.message ?: throwable::class.simpleName ?: "unknown",
        )
    }

    val elapsedMs = mark.elapsedNow().inWholeMilliseconds
    val environmentJson = buildJsonObject {
        put("target", ctx.diagnostics?.target ?: "context-provided")
        put("commitSha", "unknown")
        put("ts", LocalDateTime.now().toString())
        ctx.diagnostics?.environment?.forEach { (key, value) -> put(key, value) }
    }.toString()
    val artifactPath = try {
        ArtifactDumper.dumpIfFailed(
            scenarioName = name,
            reporter = store.reporter,
            rawArtifacts = ctx.diagnostics?.rawArtifacts.orEmpty(),
            projectedFactsJson = store.snapshotProjectedFactsJson(),
            environmentJson = environmentJson,
        )
    } catch (throwable: Throwable) {
        println("[WARN] artifact dump 抛异常已忽略：${throwable.message}")
        null
    }
    if (artifactPath != null) println("artifact: $artifactPath")

    ScenarioResult(
        name = name,
        pass = !store.reporter.hasFailure(),
        reporter = store.reporter,
        elapsedMs = elapsedMs,
    )
}

private suspend fun runExpectFact(
    store: E2eStore,
    expectFact: ExpectFactScope.(Map<String, Any?>) -> Unit,
    baseline: Map<String, Any?>,
) {
    val scope = ExpectFactScope()
    expectFact(scope, baseline)
    val pending = scope.sourceExpectations.toMutableMap()
    val started = TimeSource.Monotonic.markNow()

    while (pending.isNotEmpty()) {
        val observed = store.observeSourceFactResults(pending.keys)

        val completed = mutableSetOf<String>()
        pending.forEach { (sourceName, expectation) ->
            val sourceResult = observed.getValue(sourceName)
            if (sourceResult.isFailure) {
                val throwable = sourceResult.exceptionOrNull()!!
                store.reporter.step(
                    "source[$sourceName]",
                    pass = false,
                    failReason = "source Fact 采集失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
                )
                completed += sourceName
                return@forEach
            }
            val actual = sourceResult.getOrNull()
            val expected = try {
                expectation.transform(actual)
            } catch (throwable: Throwable) {
                store.reporter.step(
                    "source[$sourceName]",
                    pass = false,
                    failReason = "期望转换失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
                )
                completed += sourceName
                return@forEach
            }

            if (actual == expected) {
                store.reporter.step("source[$sourceName]", pass = true)
                completed += sourceName
            } else {
                val timeout = expectation.timeout ?: store.defaultSourceTimeout
                if (started.elapsedNow() >= timeout) {
                    store.reporter.step(
                        "source[$sourceName]",
                        pass = false,
                        failReason = "expected=$expected, actual=$actual",
                    )
                    completed += sourceName
                }
            }
        }
        completed.forEach(pending::remove)
        if (pending.isNotEmpty()) delay(store.pollInterval)
    }

    runSourceConsistencyChecks(store, scope)
}

internal suspend fun runSourceConsistencyChecks(store: E2eStore, scope: ExpectFactScope) {
    if (scope.sourceConsistencyChecks.isEmpty()) return
    val facts = try {
        store.observeSourceFacts()
    } catch (throwable: Throwable) {
        store.reporter.step(
            "source_consistency_facts",
            pass = false,
            failReason = "source Fact 采集失败: ${throwable.javaClass.simpleName}: ${throwable.message}",
        )
        return
    }
    scope.sourceConsistencyChecks.forEach { check ->
        val pass = check.predicate(facts)
        store.reporter.step(
            check.describe,
            pass = pass,
            failReason = if (pass) null else "source facts=$facts",
        )
    }
}

private suspend fun runExpectDependencies(
    store: E2eStore,
    expectDependencies: ExpectDependencyScope.() -> Unit,
) {
    val scope = ExpectDependencyScope().apply(expectDependencies)
    val events = store.eventsAfterSeq(store.actStartSeq)
    scope.checks.forEach { check ->
        val pass = check.predicate(events)
        store.reporter.step(
            check.describe,
            pass = pass,
            failReason = if (pass) null else "events since actStart 里未命中；events.size=${events.size}",
        )
    }
}
