package dev.handeye.demo

import android.content.Context
import android.util.LruCache
import dev.handeye.device.EventRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** 内存缓存（LruCache）+ SQLite 持久化 + OkHttp API 的组装层。 */
class FeedRepository(
    context: Context,
    private val recorder: EventRecorder,
    private val api: FeedApi,
) {
    val cache = LruCache<Int, FeedItem>(200)
    private val db = FeedDb(context)

    suspend fun refresh(): List<FeedItem> = withContext(Dispatchers.IO) {
        val page = api.fetchPage(1)
        page.items.forEach { cache.put(it.id, it) }
        recorder.record("cacheWrite", "feed", buildJsonObject { put("entryCount", cache.size()) })
        db.replaceAll(page.items)
        recorder.record("dbWrite", "feed", buildJsonObject { put("rowCount", page.items.size) })
        page.items
    }

    suspend fun toggleLike(id: Int): FeedItem? = withContext(Dispatchers.IO) {
        val item = cache.get(id) ?: return@withContext null
        val updated = item.copy(liked = !item.liked)
        cache.put(id, updated)
        recorder.record("cacheWrite", "feed", buildJsonObject { put("key", "like:$id") })
        db.setLiked(id, updated.liked)
        recorder.record("dbWrite", "feed", buildJsonObject { put("key", "like:$id") })
        updated
    }

    fun snapshotCache(): JsonArray = buildJsonArray {
        cache.snapshot().values.forEach { item ->
            add(buildJsonObject {
                put("id", item.id)
                put("title", item.title)
                put("liked", item.liked)
            })
        }
    }

    fun snapshotPersist(): JsonArray = db.dumpAll()

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cache.evictAll()
        db.clear()
    }

    suspend fun dropMemoryCache() = withContext(Dispatchers.IO) {
        cache.evictAll()
    }
}

class FeedApi(private val client: OkHttpClient) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    suspend fun fetchPage(page: Int): FeedPage = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("https://demo.local/feed?page=$page")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 200) error("HTTP ${resp.code}")
            json.decodeFromString(FeedPage.serializer(), resp.body!!.string())
        }
    }
}
