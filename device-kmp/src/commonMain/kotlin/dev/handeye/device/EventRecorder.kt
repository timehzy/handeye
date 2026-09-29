package dev.handeye.device

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * 事件记录器：把注册的 Flow 元素与手动 record 调用统一写入 events.jsonl。
 *
 * 并发模型：事件信封构造后进入无界命令队列，单 writer 协程顺序处理（含阻塞写盘）；
 * seq 分配与入队在同一锁内完成，保证 seq、队列顺序、写盘顺序一致。
 * [flush] 等待队列排空，是写盘可见性 barrier——/events 读取前调用。
 * [reset] 清文件并重置时钟基点，seq 不回退。
 */
class EventRecorder(
    private val logFilePath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {
    private sealed interface WriteCommand {
        data class Append(val line: String) : WriteCommand
        data class Flush(val ack: CompletableDeferred<Unit>) : WriteCommand
        data object Reset : WriteCommand
    }

    private val enqueueLock = SynchronizedObject()
    private val writeCommands = Channel<WriteCommand>(Channel.UNLIMITED)
    private var eventSeq = 0L

    @Volatile
    private var monotonicMark = TimeSource.Monotonic.markNow()

    private val subscriptionsReady = CompletableDeferred<Unit>()
    private val subscriptionsLock = SynchronizedObject()
    private var expectedSubscriptions = 0
    private var readySubscriptions = 0

    private val writerJob = scope.launch {
        for (command in writeCommands) {
            when (command) {
                is WriteCommand.Append -> writeLine(command.line)
                is WriteCommand.Flush -> command.ack.complete(Unit)
                WriteCommand.Reset -> resetFile()
            }
        }
    }

    /** 订阅一条 JsonObject Flow，逐元素包装为事件落盘；nameKey 指定 name 取值字段，缺省用 kind。 */
    fun registerFlow(kind: String, nameKey: String, flow: () -> Flow<JsonObject>) {
        registerFlow(kind, nameKey, { it }, flow)
    }

    /** 订阅一条任意类型 Flow，serialize 转 JSON；nameKey 指定 name 取值字段，缺省用 kind。 */
    fun <T> registerFlow(
        kind: String,
        nameKey: String,
        serialize: (T) -> JsonObject,
        flow: () -> Flow<T>,
    ) {
        synchronized(subscriptionsLock) { expectedSubscriptions++ }
        scope.launch {
            val upstream = flow()
            val collectElement: (T) -> Unit = { element ->
                val payload = serialize(element)
                val name = (payload[nameKey] as? JsonPrimitive)?.content ?: kind
                enqueue(kind, name, payload)
            }
            // SharedFlow(replay=0) 在订阅建立前发射会丢事件，以 onSubscription 为就绪信号；
            // 冷流没有丢事件窗口，collect 启动即就绪。
            if (upstream is SharedFlow<T>) {
                upstream.onSubscription { markSubscriptionReady() }
                    .collect(collectElement)
            } else {
                markSubscriptionReady()
                upstream.collect(collectElement)
            }
        }
    }

    private fun markSubscriptionReady() {
        synchronized(subscriptionsLock) {
            readySubscriptions++
            if (readySubscriptions >= expectedSubscriptions && !subscriptionsReady.isCompleted) {
                subscriptionsReady.complete(Unit)
            }
        }
    }

    /** 回调式记录：拦截器 / observer 等非 Flow 场景。 */
    fun record(kind: String, name: String, payload: JsonObject? = null) {
        enqueue(kind, name, payload)
    }

    /** 等全部已注册 Flow 的订阅建立；超时返 false。 */
    suspend fun awaitSubscriptionsReady(timeoutMs: Long = 5000): Boolean {
        withTimeoutOrNull(timeoutMs) { subscriptionsReady.await() }
        return subscriptionsReady.isCompleted
    }

    /** 写盘可见性 barrier：返回时此前所有事件已落盘。 */
    suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        writeCommands.send(WriteCommand.Flush(ack))
        ack.await()
    }

    /** 清事件文件并重置时钟基点；seq 不回退。 */
    fun reset() {
        writeCommands.trySend(WriteCommand.Reset)
    }

    fun uninstall() {
        scope.cancel()
    }

    /** seq 分配与入队在同一锁内完成，保证 seq 顺序与队列顺序一致。 */
    private fun enqueue(kind: String, name: String, payload: JsonObject?) {
        synchronized(enqueueLock) {
            val envelope = buildJsonObject {
                put("seq", ++eventSeq)
                put("t", monotonicMark.elapsedNow().inWholeMilliseconds)
                put("kind", kind)
                put("name", name)
                if (payload != null) put("payload", payload)
            }
            writeCommands.trySend(WriteCommand.Append(envelope.toString()))
        }
    }

    private fun resetFile() {
        val path = logFilePath.toPath()
        fileSystem.delete(path)
        monotonicMark = TimeSource.Monotonic.markNow()
    }

    private fun writeLine(line: String) {
        val path = logFilePath.toPath()
        path.parent?.let { parent ->
            if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent)
        }
        fileSystem.appendingSink(path).buffer().use { sink ->
            sink.writeUtf8(line)
            sink.writeUtf8("\n")
        }
    }
}
