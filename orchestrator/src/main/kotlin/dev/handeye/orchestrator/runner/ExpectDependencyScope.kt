package dev.handeye.orchestrator.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 依赖断言 scope —— 补 Fact 覆盖不到的东西（如 playerCmd 序列、editModuleCmd op、effect 派发）。
 *
 * runtime 在 Phase 5 通过 `GET /events?since=<actStartT>` 拉主动作后 events，按 kind + matcher 断言。
 *
 * 每个 [expectXxx] 登记一个 [Check]，SoftAssertionReporter 汇总。
 */
class ExpectDependencyScope {

    /** 单个依赖断言 —— events 列表输入，pass/fail + describe 输出。 */
    class Check(
        val describe: String,
        val predicate: (List<JsonObject>) -> Boolean,
    )

    internal val checks = mutableListOf<Check>()

    /** 通用事件期望：至少存在一个 kind/name/matcher 都命中的事件。 */
    fun expectEvent(
        kind: String,
        name: String,
        nameKey: String = "name",
        matcher: JsonObject.() -> Boolean = { true },
    ) {
        checks += Check("$kind $nameKey = $name") { events ->
            events.any { ev ->
                ev.asKind() == kind &&
                    ev.asNameOf(nameKey) == name &&
                    ev.matcher()
            }
        }
    }

    /** 通用事件计数期望。 */
    fun expectEventCount(
        kind: String,
        name: String,
        count: Int,
        nameKey: String = "name",
        matcher: JsonObject.() -> Boolean = { true },
    ) {
        checks += Check("$kind $nameKey = $name count = $count") { events ->
            events.count { ev ->
                ev.asKind() == kind &&
                    ev.asNameOf(nameKey) == name &&
                    ev.matcher()
            } == count
        }
    }

    /** 通用事件否定期望 —— 不存在 `kind = [kind]` 且 `[nameKey] = [name]` 的事件。 */
    fun expectNoEvent(kind: String, name: String, nameKey: String = "name") {
        expectEventCount(
            kind = kind,
            name = name,
            nameKey = nameKey,
            count = 0,
        )
    }

    /** 通用否定期望，业务层提供 matcher 与描述，不要求 framework 理解业务字段。 */
    fun expectNoMatchingEvent(
        kind: String,
        describe: String,
        matcher: (JsonObject) -> Boolean,
    ) {
        checks += Check("no $kind $describe") { events ->
            events.none { event -> event.asKind() == kind && matcher(event) }
        }
    }

    private fun JsonObject.asKind(): String? =
        (this["kind"] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.asNameOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
