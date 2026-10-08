package dev.handeye.demo

import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * bootstrap deeplink 解析：handeye://bootstrap?work_path=demo → bootstrap 源载荷。
 *
 * 解析结果就是普通 JsonObject，走首期 StateProvider API，不引入第二套机制。
 */
object BootstrapIntentParser {
    fun parse(intent: Intent?): JsonObject? {
        val data: Uri = intent?.data ?: return null
        val query = buildMap<String, String?> {
            data.queryParameterNames.forEach { name -> put(name, data.getQueryParameter(name)) }
        }
        return parseCore(
            scheme = data.scheme,
            host = data.host,
            query = query,
            coldStart = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    /** 纯映射核心：不碰 android 类型，JVM 单测直接覆盖。 */
    fun parseCore(
        scheme: String?,
        host: String?,
        query: Map<String, String?>,
        coldStart: Boolean,
    ): JsonObject? {
        if (scheme != BuildConfig.HANDEYE_SCHEME || host != "bootstrap") return null
        return buildJsonObject {
            query.forEach { (name, value) -> put(name, value ?: "") }
            put("_coldStart", coldStart)
        }
    }
}
