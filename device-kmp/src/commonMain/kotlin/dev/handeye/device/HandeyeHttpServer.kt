package dev.handeye.device

/** 内嵌 HTTP 引擎抽象：平台 actual 或接入方注入实现。实现必须只绑 127.0.0.1。 */
interface HandeyeHttpServer {
    fun start(port: Int)
    fun stop()
    fun registerHandler(method: String, path: String, handler: (HttpRequest) -> HttpResponse)
}

data class HttpRequest(
    val query: Map<String, String>,
    val body: String?,
)

data class HttpResponse(
    val code: Int,
    val body: String,
)
