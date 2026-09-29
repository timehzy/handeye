package dev.handeye.demo

import dev.handeye.device.EventRecorder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 请求/响应事件记录 + 预制响应短路。OkHttp 调用栈真实执行（序列化、线程切换），
 * 响应由 [pageProvider] 给出，不经 socket——demo 的确定性来源。
 */
class HandeyeInterceptor(
    private val recorder: EventRecorder,
    private val pageProvider: (Int) -> Pair<Int, String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val name = req.method + " " + req.url.encodedPath
        recorder.record(
            "networkRequest", name,
            buildJsonObject { req.url.queryParameter("page")?.toIntOrNull()?.let { put("page", it) } },
        )
        val (status, body) = pageProvider(req.url.queryParameter("page")?.toIntOrNull() ?: 1)
        val response = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        recorder.record("networkResponse", name, buildJsonObject { put("status", status) })
        return response
    }
}
