package dev.handeye.demo

import android.content.Context
import android.util.LruCache
import dev.handeye.device.EventRecorder
import kotlinx.coroutines.CompletableDeferred
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

    // 进行中的 refresh 共享同一次结果（single-flight）：并发触发不重复打网络、不重复写库。
    private val refreshMonitor = Any()
    private var refreshInFlight: CompletableDeferred<List<FeedItem>>? = null

    suspend fun refresh(): List<FeedItem> {
        val flight: CompletableDeferred<List<FeedItem>>
        val owner: Boolean
        synchronized(refreshMonitor) {
            val existing = refreshInFlight
            if (existing != null) {
                flight = existing
                owner = false
            } else {
                flight = CompletableDeferred()
                refreshInFlight = flight
                owner = true
            }
        }
        if (!owner) return flight.await()
        try {
            val items = doRefresh()
            flight.complete(items)
            return items
        } catch (t: Throwable) {
            flight.completeExceptionally(t)
            throw t
        } finally {
            synchronized(refreshMonitor) { if (refreshInFlight === flight) refreshInFlight = null }
        }
    }

    /**
     * 冷启动装载：内存缓存非空直接用；否则从持久化回填内存。无数据来源返 null。
     * 全程不打网络 —— 网络只在用户主动刷新时发生。
     */
    suspend fun loadInitial(): List<FeedItem>? = withContext(Dispatchers.IO) {
        val cached = cache.snapshot().values.sortedBy { it.id }
        if (cached.isNotEmpty()) return@withContext cached
        val persisted = db.loadAll()
        if (persisted.isEmpty()) return@withContext null
        persisted.forEach { cache.put(it.id, it) }
        recorder.record("cacheWrite", "feed", buildJsonObject { put("entryCount", cache.size()) })
        persisted
    }

    private suspend fun doRefresh(): List<FeedItem> = withContext(Dispatchers.IO) {
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
        // 按 id 排序输出，与 persist 源的 ORDER BY id 对齐，保证两源快照可直接比对。
        cache.snapshot().values.sortedBy { it.id }.forEach { item ->
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
