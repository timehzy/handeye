package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.http.OrchestratorHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用 JDK 内置 `com.sun.net.httpserver.HttpServer` 做 mock 服务端，验证
 * [OrchestratorHttpClient] 的 request/response 语义 —— status code / body 透传、query 参数
 * 编码、POST body 与 Content-Type 正确写入。
 */
class HttpClientTest {

    private lateinit var server: HttpServer
    private lateinit var client: OrchestratorHttpClient
    private var port: Int = 0

    @BeforeTest
    fun setup() {
        // 多线程 executor：单线程默认 executor 会让「首请求故意慢」阻塞后续重试请求的接受。
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        port = server.address.port
        server.start()
        client = OrchestratorHttpClient("http://127.0.0.1:$port")
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
    }

    @Test
    fun get_returns_status_and_body() {
        server.createContext("/hello") { exchange ->
            val body = "hi".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val resp = client.get("/hello")
        assertEquals(200, resp.statusCode)
        assertEquals("hi", resp.body)
    }

    @Test
    fun get_with_query_params_encoded() {
        var receivedQuery: String? = null
        server.createContext("/echo") { exchange ->
            receivedQuery = exchange.requestURI.rawQuery
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        client.get("/echo", mapOf("k" to "v v", "since" to "100"))
        assertNotNull(receivedQuery)
        // URLEncoder 默认把空格编成 `+`
        assertTrue(
            receivedQuery!!.contains("k=v+v") || receivedQuery!!.contains("k=v%20v"),
            "query=$receivedQuery",
        )
        assertTrue(receivedQuery!!.contains("since=100"), "query=$receivedQuery")
    }

    @Test
    fun post_sends_body_and_content_type() {
        var receivedBody: String? = null
        var receivedType: String? = null
        server.createContext("/post") { exchange ->
            receivedType = exchange.requestHeaders.getFirst("Content-Type")
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val resp = client.post("/post", """{"a":1}""")
        assertEquals(200, resp.statusCode)
        assertEquals("application/json", receivedType)
        assertEquals("""{"a":1}""", receivedBody)
    }

    @Test
    fun post_custom_content_type_is_honored() {
        var receivedType: String? = null
        server.createContext("/text") { exchange ->
            receivedType = exchange.requestHeaders.getFirst("Content-Type")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        client.post("/text", "plain body", contentType = "text/plain")
        assertEquals("text/plain", receivedType)
    }

    @Test
    fun get_non_2xx_passes_status_through() {
        server.createContext("/miss") { exchange ->
            val body = """{"error":"not found"}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val resp = client.get("/miss")
        assertEquals(404, resp.statusCode)
        assertEquals("""{"error":"not found"}""", resp.body)
    }

    @Test
    fun get_retries_after_transient_timeout_then_succeeds() {
        val calls = AtomicInteger(0)
        server.createContext("/slow-then-fast") { exchange ->
            if (calls.incrementAndGet() == 1) {
                // 首次请求故意越过客户端超时，模拟批跑中段 server 响应变慢。
                Thread.sleep(200)
            }
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // timeoutMs 调到 50ms，让首次请求在服务端 200ms 响应前就超时。
        val slowClient = OrchestratorHttpClient("http://127.0.0.1:$port", timeoutMs = 50)
        val resp = slowClient.get("/slow-then-fast")
        assertEquals(200, resp.statusCode)
        assertEquals("ok", resp.body)
        assertEquals(2, calls.get())
    }

    @Test
    fun get_gives_up_after_max_attempts() {
        val calls = AtomicInteger(0)
        server.createContext("/always-slow") { exchange ->
            calls.incrementAndGet()
            Thread.sleep(200)
            val body = "late".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val slowClient = OrchestratorHttpClient(
            "http://127.0.0.1:$port",
            timeoutMs = 50,
            maxAttempts = 3,
        )
        assertFailsWith<HttpTimeoutException> {
            slowClient.get("/always-slow")
        }
        assertEquals(3, calls.get())
    }
}
