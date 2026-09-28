package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 单个 scenario 的运行时 handle。所有 Fact 都以 source-name map 承载。
 */
class E2eStore(
    private val ctx: E2eContext,
) {

    val reporter = SoftAssertionReporter()

    var actStartSeq: Long = 0L
        private set

    private var lastSourceFacts: Map<String, Any?> = emptyMap()

    private val mark = TimeSource.Monotonic.markNow()

    internal val allSourceNames: Set<String>
        get() = ctx.factSources.keys

    internal val defaultSourceTimeout: Duration
        get() = ctx.defaults.perSourceTimeout

    internal val pollInterval: Duration
        get() = ctx.defaults.pollInterval

    fun captureActStart() {
        val events = runBlocking {
            ctx.eventsFetcher.fetchAfter(seq = 0L, kinds = null, limit = ctx.defaults.eventsBatchLimit)
        }
        actStartSeq = events.maxOfOrNull { it.seq } ?: 0L
    }

    suspend fun observeSourceFacts(sourceNames: Set<String> = allSourceNames): Map<String, Any?> =
        observeSourceFactResults(sourceNames).mapValues { (_, result) -> result.getOrThrow() }

    internal suspend fun observeSourceFactResults(
        sourceNames: Set<String> = allSourceNames,
    ): Map<String, Result<Any?>> =
        coroutineScope {
            val deferred = sourceNames.associateWith { sourceName ->
                async {
                    runCatching {
                        val source = ctx.factSources[sourceName]
                            ?: error("E2eContext 缺少事实源 '$sourceName'")
                        source.fetch() ?: error("$sourceName 事实源拉取失败（HTTP 非 200 或解析失败）")
                    }
                }
            }
            deferred.mapValues { (_, fact) -> fact.await() }.also { results ->
                lastSourceFacts = lastSourceFacts + results.mapNotNull { (sourceName, result) ->
                    result.getOrNull()?.let { sourceName to it }
                }
            }
        }

    fun snapshotProjectedFactsJson(): String = buildJsonObject {
        lastSourceFacts.forEach { (sourceName, fact) ->
            put(sourceName, fact?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }
    }.toString()

    suspend fun resetEvents() {
        ctx.eventsReset?.invoke()
    }

    suspend fun checkHealth(): Boolean = ctx.healthCheck?.invoke() ?: true

    suspend fun dispatchIntent(intentClass: String, args: Map<String, Any?>): ActScope.DispatchResult {
        val result = ctx.dispatcher.dispatch(intentClass, args.toJsonPayload())
        return ActScope.DispatchResult(
            dispatched = result.dispatched,
            consumerTag = result.consumerTag,
            stateSnapshotRaw = result.stateSnapshot?.toString() ?: "null",
        )
    }

    suspend fun dispatchControl(path: String, args: Map<String, Any?>): ActScope.DispatchResult {
        val result = ctx.dispatcher.dispatchControl(path, args.toJsonPayload())
        return ActScope.DispatchResult(
            dispatched = result.dispatched,
            consumerTag = result.consumerTag,
            stateSnapshotRaw = result.stateSnapshot?.toString() ?: "null",
            errorMessage = result.errorMessage,
        )
    }

    fun eventsAfterSeq(afterSeq: Long): List<JsonObject> = runBlocking {
        ctx.eventsFetcher.fetchAfter(
            seq = afterSeq,
            kinds = null,
            limit = ctx.defaults.eventsBatchLimit,
        )
    }.map { it.raw }

    suspend fun awaitEventBarrier(
        kind: String,
        name: String,
        nameKey: String,
        timeout: Duration,
    ) {
        val deadlineMs = mark.elapsedNow().inWholeMilliseconds + timeout.inWholeMilliseconds
        while (mark.elapsedNow().inWholeMilliseconds < deadlineMs) {
            val hit = eventsAfterSeq(actStartSeq).any { event ->
                (event["kind"] as? JsonPrimitive)?.contentOrNull == kind &&
                    (event[nameKey] as? JsonPrimitive)?.contentOrNull == name
            }
            if (hit) return
            delay(ctx.defaults.pollInterval)
        }
        throw AssertionError(
            "awaitEventBarrier($kind, $nameKey=$name) 超时（${timeout.inWholeMilliseconds}ms）",
        )
    }

    suspend fun awaitFact(
        timeout: Duration,
        predicate: (Map<String, Any?>) -> Boolean,
    ) {
        val deadlineMs = mark.elapsedNow().inWholeMilliseconds + timeout.inWholeMilliseconds
        while (mark.elapsedNow().inWholeMilliseconds < deadlineMs) {
            if (predicate(observeSourceFacts())) return
            delay(ctx.defaults.pollInterval)
        }
        throw AssertionError("awaitFact 超时（${timeout.inWholeMilliseconds}ms）")
    }
}

/**
 * 把 scenario 侧的 [Map] payload 递归转成 [JsonObject]，支持嵌套 [Map]（→ [JsonObject]）与
 * [Iterable]（→ JsonArray）。primitive 分支（null / Number / Boolean / String）与既有 dispatch
 * 契约完全等价，既有全 primitive scenario 无行为变化。
 *
 * 复合参数 Intent（如 `Timeline.TrimEnd` 的 `target: TimelineSelection` sealed + `newRange: Section`
 * 值对象）在 scenario 侧写作 `mapOf("target" to mapOf("kind" to "Overlay", "id" to ..., "type" to ...), ...)`
 * 后经由本转换器落成合法 JsonObject 送达 debug server，由 registry factory 解析。
 *
 * key 用 `toString()` 兜底非 String 键；未识别的 value 类型走 `JsonPrimitive(toString())` 兜底、
 * 与旧行为一致——保留兜底避免破坏既有 primitive scenario 中传入自定义 enum / value class 的写法。
 */
internal fun Map<String, Any?>.toJsonPayload(): JsonObject = buildJsonObject {
    forEach { (key, value) -> put(key, value.toJsonElement()) }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in this@toJsonElement) put(k.toString(), v.toJsonElement())
    }
    is Iterable<*> -> buildJsonArray {
        for (v in this@toJsonElement) add(v.toJsonElement())
    }
    else -> JsonPrimitive(toString())
}

internal class ActScopeImpl(
    private val store: E2eStore,
    private var strictness: StrictnessMode = StrictnessMode.NonExhaustive,
) : ActScope {

    override suspend fun dispatch(
        intentClass: String,
        args: Map<String, Any?>,
    ): ActScope.DispatchResult = store.dispatchIntent(intentClass, args)

    override suspend fun dispatchControl(
        path: String,
        payload: Map<String, Any?>,
    ): ActScope.DispatchResult = store.dispatchControl(path, payload)

    override suspend fun advanceTime(duration: Duration) {
        delay(duration)
    }

    override suspend fun awaitEvent(
        kind: String,
        name: String,
        nameKey: String,
        timeout: Duration,
    ) {
        store.awaitEventBarrier(kind, name, nameKey, timeout)
    }

    override suspend fun awaitFact(
        timeout: Duration,
        predicate: (Map<String, Any?>) -> Boolean,
    ) {
        store.awaitFact(timeout, predicate)
    }

    override suspend fun withStrict(block: suspend ActScope.() -> Unit) {
        val previous = strictness
        strictness = StrictnessMode.Strict
        try {
            block()
        } finally {
            strictness = previous
        }
    }
}
