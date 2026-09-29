package dev.handeye.device

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** Ktor CIO 实现的内嵌 server，只绑 127.0.0.1。只在 androidDebug 变体编译。 */
class KtorHandeyeHttpServer : HandeyeHttpServer {
    private data class HandlerEntry(
        val method: String,
        val path: String,
        val handler: suspend (HttpRequest) -> HttpResponse,
    )

    private val handlers = mutableListOf<HandlerEntry>()

    @Volatile
    private var engine: ApplicationEngine? = null

    override fun registerHandler(method: String, path: String, handler: suspend (HttpRequest) -> HttpResponse) {
        handlers += HandlerEntry(method, path, handler)
    }

    override fun start(port: Int) {
        val snapshot = handlers.toList()
        engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                snapshot.forEach { entry ->
                    if (entry.method == "GET") {
                        get(entry.path) { call.respondEntry(entry) }
                    } else {
                        post(entry.path) { call.respondEntry(entry) }
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun ApplicationCall.respondEntry(entry: HandlerEntry) {
        val query = request.queryParameters.entries()
            .associate { e -> e.key to e.value.first() }
        val body = runCatching { receiveText() }.getOrNull()
        val response = entry.handler(HttpRequest(query, body))
        respondText(
            text = response.body,
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.fromValue(response.code),
        )
    }

    override fun stop() {
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        engine = null
    }
}

internal actual fun createPlatformServer(): HandeyeHttpServer = KtorHandeyeHttpServer()
