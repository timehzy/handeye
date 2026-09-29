package dev.handeye.demo.e2e.scenarios

import dev.handeye.demo.e2e.FeedMemoryFact
import dev.handeye.demo.e2e.FeedPersistFact
import dev.handeye.demo.e2e.FeedUiFact
import dev.handeye.demo.e2e.feedContext
import dev.handeye.orchestrator.runner.ExpectFactScope
import dev.handeye.orchestrator.runner.ScenarioResult
import dev.handeye.orchestrator.runner.handeyeTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun uiFact(facts: Map<String, Any?>): FeedUiFact? = facts["ui"] as? FeedUiFact

private fun memoryFact(facts: Map<String, Any?>): FeedMemoryFact? = facts["memory"] as? FeedMemoryFact

private fun persistFact(facts: Map<String, Any?>): FeedPersistFact? = facts["persist"] as? FeedPersistFact

/** 从 items 快照（[{id,title,liked},...]）里取出 liked=true 的 id 列表。 */
private fun likedIds(items: JsonArray?): List<Int> =
    items?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        if (obj["liked"]?.jsonPrimitive?.booleanOrNull == true) {
            obj["id"]?.jsonPrimitive?.intOrNull
        } else {
            null
        }
    }.orEmpty()

private fun JsonObject.payload(): JsonObject? = this["payload"] as? JsonObject

private fun JsonObject.responseStatus(): Int? = payload()?.get("status")?.jsonPrimitive?.intOrNull

private fun JsonObject.payloadKey(): String? = payload()?.get("key")?.jsonPrimitive?.contentOrNull

private const val FEED_PAGE_SIZE = 20

/** Fact 断言模板：三源都是 20 条且内存与持久化内容一致。 */
private fun ExpectFactScope.expectFeedFullyLoaded() {
    expectSource<FeedUiFact>("ui") { copy(isLoading = false, listSize = FEED_PAGE_SIZE, error = null) }
    expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
    expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
    consistency("内存与持久化 items 完全一致") { facts ->
        memoryFact(facts)?.items == persistFact(facts)?.items
    }
}

/** 场景 1：下拉刷新 —— 用户操作触发网络请求，内存/持久化/UI 三源同步到最新数据。 */
fun runRefreshShowsLatestFeed(baseUrl: String): ScenarioResult = handeyeTest(
    name = "refresh_shows_latest_feed",
    ctx = feedContext(baseUrl),
    block = {
        act { dispatch("Feed.Refresh") }
        expectFact { expectFeedFullyLoaded() }
        expectDependencies {
            expectEvent("networkRequest", "GET /feed")
            expectEvent("networkResponse", "GET /feed") { responseStatus() == 200 }
            expectEventCount("cacheWrite", "feed", 1)
            expectEventCount("dbWrite", "feed", 1)
        }
    },
)

/** 场景 2：刷新失败 —— 网络 500 时 UI 报错，已有内存/持久化数据不被破坏。 */
fun runRefreshFailureKeepsCache(baseUrl: String): ScenarioResult = handeyeTest(
    name = "refresh_failure_keeps_cache",
    ctx = feedContext(baseUrl),
    block = {
        givenState {
            dispatch("Feed.Refresh")
            awaitFact { memoryFact(it)?.entryCount == FEED_PAGE_SIZE }
        }
        act {
            dispatch("Feed._FailNext")
            dispatch("Feed.Refresh")
        }
        expectFact {
            expectSource<FeedUiFact>("ui") {
                copy(isLoading = false, listSize = FEED_PAGE_SIZE, error = "HTTP 500")
            }
            expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
            expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
            consistency("失败不破坏已有数据") { facts ->
                memoryFact(facts)?.items == persistFact(facts)?.items
            }
        }
        expectDependencies {
            expectEvent("networkRequest", "GET /feed")
            expectEvent("networkResponse", "GET /feed") { responseStatus() == 500 }
            expectNoEvent("cacheWrite", "feed")
            expectNoEvent("dbWrite", "feed")
        }
    },
)

/** 场景 3：重复刷新 —— 先后两次完整 Refresh 各产生一次网络请求与写库，数据始终一致（重复执行安全）。 */
fun runRefreshIsIdempotent(baseUrl: String): ScenarioResult = handeyeTest(
    name = "refresh_is_idempotent",
    ctx = feedContext(baseUrl),
    block = {
        act {
            dispatch("Feed.Refresh")
            // 等第一次刷新落定再发第二次——dispatch 只保证命令送达，不保证业务完成；
            // 连续盲发两次可能一前一后错过 single-flight 窗口，各自打一次网络，幂等断言就飘了。
            awaitFact { uiFact(it)?.isLoading == false && uiFact(it)?.listSize == FEED_PAGE_SIZE }
            dispatch("Feed.Refresh")
        }
        expectFact { expectFeedFullyLoaded() }
        expectDependencies {
            expectEventCount("networkRequest", "GET /feed", 2)
            expectEventCount("cacheWrite", "feed", 2)
            expectEventCount("dbWrite", "feed", 2)
        }
    },
)

/** 场景 4：点赞 —— 用户操作写穿内存缓存与持久化，UI 即时反映。 */
fun runToggleLikePersists(baseUrl: String): ScenarioResult = handeyeTest(
    name = "toggle_like_persists",
    ctx = feedContext(baseUrl),
    block = {
        givenState {
            dispatch("Feed.Refresh")
            awaitFact { memoryFact(it)?.entryCount == FEED_PAGE_SIZE }
        }
        act { dispatch("Item.ToggleLike", mapOf("id" to 1)) }
        expectFact {
            expectSource<FeedUiFact>("ui") { copy(likedIds = listOf(1)) }
            expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
            expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
            consistency("点赞写入内存与持久化") { facts ->
                likedIds(memoryFact(facts)?.items) == listOf(1) && likedIds(persistFact(facts)?.items) == listOf(1)
            }
        }
        expectDependencies {
            expectEvent("dbWrite", "feed") { payloadKey() == "like:1" }
        }
    },
)

/** 场景 5：点赞 survives ViewModel 重建 —— 重建后 UI 从内存恢复点赞态，全程零网络请求。 */
fun runLikeSurvivesRecreate(baseUrl: String): ScenarioResult = handeyeTest(
    name = "like_survives_recreate",
    ctx = feedContext(baseUrl),
    block = {
        givenState {
            dispatch("Feed.Refresh")
            awaitFact { memoryFact(it)?.entryCount == FEED_PAGE_SIZE }
            dispatch("Item.ToggleLike", mapOf("id" to 1))
            awaitFact { likedIds(persistFact(it)?.items) == listOf(1) }
        }
        act {
            dispatch("Feed._Recreate")
            awaitFact { uiFact(it)?.listSize == FEED_PAGE_SIZE && uiFact(it)?.likedIds == listOf(1) }
        }
        expectFact {
            expectSource<FeedUiFact>("ui") {
                copy(isLoading = false, listSize = FEED_PAGE_SIZE, likedIds = listOf(1))
            }
            expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
            expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
            consistency("重建后三源点赞状态一致") { facts ->
                uiFact(facts)?.likedIds == listOf(1) &&
                    likedIds(memoryFact(facts)?.items) == listOf(1) &&
                    likedIds(persistFact(facts)?.items) == listOf(1)
            }
        }
        expectDependencies {
            expectNoEvent("networkRequest", "GET /feed")
        }
    },
)

/** 场景 6：冷启动走持久化 —— 内存清空后重建，UI 由 DB 回填恢复，零网络请求。 */
fun runColdStartFromCache(baseUrl: String): ScenarioResult = handeyeTest(
    name = "cold_start_from_cache",
    ctx = feedContext(baseUrl),
    block = {
        givenState {
            dispatch("Feed.Refresh")
            awaitFact { memoryFact(it)?.entryCount == FEED_PAGE_SIZE }
            dispatch("Feed._DropMemory")
            awaitFact { memoryFact(it)?.entryCount == 0 }
        }
        act {
            dispatch("Feed._Recreate")
            awaitFact { uiFact(it)?.listSize == FEED_PAGE_SIZE }
        }
        expectFact {
            expectSource<FeedUiFact>("ui") { copy(isLoading = false, listSize = FEED_PAGE_SIZE, error = null) }
            expectSource<FeedMemoryFact>("memory") { copy(entryCount = FEED_PAGE_SIZE) }
            expectSource<FeedPersistFact>("persist") { copy(rowCount = FEED_PAGE_SIZE) }
            consistency("冷启动后内存由持久化回填，items 一致") { facts ->
                memoryFact(facts)?.items?.size == FEED_PAGE_SIZE &&
                    memoryFact(facts)?.items == persistFact(facts)?.items
            }
        }
        expectDependencies {
            expectNoEvent("networkRequest", "GET /feed")
        }
    },
)
