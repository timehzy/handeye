package dev.handeye.device.endpoints

import dev.handeye.device.CommandRegistry
import dev.handeye.device.EventRecorder
import dev.handeye.device.HttpResponse
import dev.handeye.device.HandeyeHttpServer
import dev.handeye.device.HandeyeInstaller
import dev.handeye.device.HandeyeJson
import dev.handeye.device.HttpRequest
import dev.handeye.device.StateProvider
import dev.handeye.device.platformProcessId
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.FileSystem
import okio.Path.Companion.toPath

/** 7 端点一次性注册；端点内部异常一律降级为带 error 字段的响应，不炸 App。 */
fun registerEndpoints(
    server: HandeyeHttpServer,
    recorder: EventRecorder,
    providers: List<StateProvider>,
    commands: CommandRegistry,
    logFilePath: String,
) {
    server.registerHandler("GET", "/health") {
        runCatching {
            val ready = recorder.awaitSubscriptionsReady()
            json(200, buildJsonObject {
                put("ok", ready)
                put("hookInstalled", HandeyeInstaller.currentPort() != null)
                put("pid", platformProcessId())
                put("logFilePath", logFilePath)
            })
        }.getOrElse { err(500, it) }
    }

    server.registerHandler("POST", "/cmd") { req ->
        runCatching {
            val body = HandeyeJson.parseToJsonElement(req.body ?: "{}").jsonObject
            val key = body["key"]?.jsonPrimitive?.content
                ?: return@runCatching err(400, IllegalArgumentException("missing key"))
            val args = body["args"]?.jsonObject ?: JsonObject(emptyMap())
            commands.dispatch(key, args)
            json(200, buildJsonObject { put("accepted", true) })
        }.getOrElse { err(400, it) }
    }

    server.registerHandler("GET", "/source") { req ->
        runCatching {
            val name = req.query["name"]
                ?: return@runCatching err(400, IllegalArgumentException("missing name"))
            val provider = providers.firstOrNull { it.name == name }
                ?: return@runCatching json(404, buildJsonObject {
                    put("source", name)
                    put("data", JsonNull)
                })
            json(200, buildJsonObject {
                put("source", name)
                put("data", provider.snapshot() ?: JsonNull)
            })
        }.getOrElse { err(500, it) }
    }

    server.registerHandler("GET", "/events") { req ->
        runCatching {
            val afterSeq = req.query["afterSeq"]?.toLongOrNull() ?: 0L
            val kinds = req.query["kinds"]?.split(",")?.filter { it.isNotBlank() }?.toSet()
            recorder.flush()
            json(200, buildJsonObject {
                putJsonArray("events") {
                    readEvents(logFilePath).forEach { line ->
                        val obj = runCatching { HandeyeJson.parseToJsonElement(line).jsonObject }
                            .getOrNull() ?: return@forEach
                        val seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val kind = obj["kind"]?.jsonPrimitive?.content ?: ""
                        if (seq > afterSeq && (kinds == null || kind in kinds)) {
                            add(obj)
                        }
                    }
                }
            })
        }.getOrElse { err(500, it) }
    }

    server.registerHandler("POST", "/reset") {
        runCatching {
            recorder.reset()
            json(200, buildJsonObject { put("reset", true) })
        }.getOrElse { err(500, it) }
    }

    server.registerHandler("POST", "/wait-for") { req ->
        runCatching {
            val body = HandeyeJson.parseToJsonElement(req.body ?: "{}").jsonObject
            val kind = body["kind"]?.jsonPrimitive?.content
                ?: return@runCatching err(400, IllegalArgumentException("missing kind"))
            val name = body["name"]?.jsonPrimitive?.content
            val afterSeq = body["afterSeq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val timeoutMs = body["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5000L
            val deadline = TimeSource.Monotonic.markNow()
            var matched = false
            while (!matched && deadline.elapsedNow().inWholeMilliseconds < timeoutMs) {
                recorder.flush()
                matched = readEvents(logFilePath).mapNotNull { line ->
                    runCatching { HandeyeJson.parseToJsonElement(line).jsonObject }.getOrNull()
                }.any { obj ->
                    val seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    obj["kind"]?.jsonPrimitive?.content == kind &&
                        (name == null || obj["name"]?.jsonPrimitive?.content == name) &&
                        seq > afterSeq
                }
                if (!matched) delay(100)
            }
            json(200, buildJsonObject { put("matched", matched) })
        }.getOrElse { err(500, it) }
    }

    server.registerHandler("POST", "/snapshot") {
        runCatching {
            json(200, buildJsonObject {
                providers.forEach { provider ->
                    put(provider.name, provider.snapshot() ?: JsonNull)
                }
            })
        }.getOrElse { err(500, it) }
    }
}

private fun readEvents(logFilePath: String): List<String> {
    val path = logFilePath.toPath()
    if (!FileSystem.SYSTEM.exists(path)) return emptyList()
    return FileSystem.SYSTEM.read(path) {
        generateSequence { readUtf8Line() }.toList()
    }
}

private fun json(code: Int, obj: JsonObject) = HttpResponse(code, obj.toString())

private fun err(code: Int, t: Throwable) = HttpResponse(
    code,
    buildJsonObject { put("error", t.message ?: t.toString()) }.toString(),
)
