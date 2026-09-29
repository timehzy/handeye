package dev.handeye.demo.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Feed demo 三源 Fact —— UI 状态 / 内存缓存 / 持久化数据各一个，字段全 nullable：
 * null 表示该次快照未覆盖此字段，断言只比对非 null 部分。
 */
data class FeedUiFact(
    val isLoading: Boolean? = null,
    val listSize: Int? = null,
    val error: String? = null,
    val likedIds: List<Int>? = null,
)

data class FeedMemoryFact(
    val entryCount: Int? = null,
    val items: JsonArray? = null,
)

data class FeedPersistFact(
    val rowCount: Int? = null,
    val items: JsonArray? = null,
)

/** device 端三个 /source 端点返回体的投影 —— 把原始 JSON 收敛成 Fact 供断言。 */
object FeedProjection {

    fun projectUi(body: JsonObject): FeedUiFact {
        val data = body["data"]?.let { it as? JsonObject } ?: body
        return FeedUiFact(
            isLoading = data["isLoading"]?.jsonPrimitive?.booleanOrNull,
            listSize = data["listSize"]?.jsonPrimitive?.intOrNull,
            error = data["error"]?.jsonPrimitive?.content?.takeIf { it != "null" },
            likedIds = data["likedIds"]?.jsonArray?.map { it.jsonPrimitive.int },
        )
    }

    fun projectMemory(body: JsonObject): FeedMemoryFact {
        val data = body["data"] as? JsonArray ?: return FeedMemoryFact()
        return FeedMemoryFact(entryCount = data.size, items = data)
    }

    fun projectPersist(body: JsonObject): FeedPersistFact {
        val data = body["data"] as? JsonArray ?: return FeedPersistFact()
        return FeedPersistFact(rowCount = data.size, items = data)
    }
}
