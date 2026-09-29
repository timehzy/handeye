package dev.handeye.orchestrator.http

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Orchestrator HTTP client —— 直接包 JDK 内置 `java.net.http.HttpClient`。
 *
 * 为什么用 JDK 内置：orchestrator 只跑在 host（Mac/Linux）上，端到端场景是 sequential
 * 请求（先 POST action，再 GET events），没有高并发或 flow 化诉求。引 Ktor client 又要
 * 选 engine，依赖膨胀且无收益。
 *
 * 用法：
 * ```
 * val http = OrchestratorHttpClient("http://<host>:<port>")
 * val resp = http.post("/<endpoint>", """{"key":"...","args":{}}""")
 * check(resp.statusCode == 200)
 * ```
 */
class OrchestratorHttpClient(
    private val baseUrl: String,
    private val timeoutMs: Long = 5_000L,
    private val maxAttempts: Int = 3,
) {
    private val http: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        // 强制 HTTP/1.1：默认的 HTTP/2 协商会被 device 端内嵌 server（Ktor CIO）忽略 Upgrade
        //  header 而挂起请求直到超时，adb forward 链路下必现。
        .version(java.net.http.HttpClient.Version.HTTP_1_1)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun get(path: String, query: Map<String, String> = emptyMap()): HttpResponse {
        val url = buildUrl(path, query)
        return withTransientRetry {
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build()
            val resp = http.send(req, BodyHandlers.ofString())
            HttpResponse(resp.statusCode(), resp.body())
        }
    }

    fun post(
        path: String,
        body: String,
        query: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): HttpResponse {
        val url = buildUrl(path, query)
        return withTransientRetry {
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, BodyHandlers.ofString())
            HttpResponse(resp.statusCode(), resp.body())
        }
    }

    /**
     * 有限次重试传输层瞬态失败（连接失败 / 读超时 / IO 中断）。批跑中段 debug server 响应变慢
     * 时单次发送会抛 [java.net.http.HttpTimeoutException]，重试有限次即可吸收。收到任何 HTTP
     * 响应（含非 2xx）都是服务端确定性答复，直接返回，重复发送会把 `/intent` 这类副作用 POST
     * 下发多次。
     */
    private fun withTransientRetry(block: () -> HttpResponse): HttpResponse {
        var lastFailure: Exception? = null
        repeat(maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IllegalStateException("请求重试耗尽但无异常记录")
    }

    /**
     * GET 请求并直接解析返回 JSON body。
     *
     * @return statusCode == 200 且 body 可解析为 [JsonObject] 时返回该对象；否则返回 null。
     */
    fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject? {
        val resp = get(path, query)
        if (resp.statusCode != 200) return null
        return runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
    }

    /**
     * POST JSON 请求并直接解析返回 JSON body。
     *
     * @return statusCode == 200 且 body 可解析为 [JsonObject] 时返回该对象；否则返回 null。
     */
    fun postJson(
        path: String,
        body: JsonObject,
        query: Map<String, String> = emptyMap(),
    ): JsonObject? {
        val resp = post(path, body.toString(), query)
        if (resp.statusCode != 200) return null
        return runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
    }

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val q = if (query.isEmpty()) {
            ""
        } else {
            "?" + query.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        }
        return "$baseUrl$path$q"
    }

    data class HttpResponse(val statusCode: Int, val body: String)
}
