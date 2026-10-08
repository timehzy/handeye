package dev.handeye.demo

import android.app.Application
import android.util.Log
import dev.handeye.device.CommandRegistry
import dev.handeye.device.EventRecorder
import dev.handeye.device.HandeyeInstaller
import dev.handeye.device.stateProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * demo 的 handeye L0 接线：事件流注册、三源快照、命令表。作为开源用户的接入模板。
 *
 * ui 源与 state 事件都读 [appUiState] —— Application 持有的稳定 StateFlow，
 * ViewModel 重建后重新转发即可，recorder 的订阅不断开。
 */
class FeedApp : Application() {
    lateinit var recorder: EventRecorder
    lateinit var viewModel: FeedViewModel
    lateinit var repository: FeedRepository

    /** ViewModel 重建后 UI 状态仍从这里读 —— recorder 订阅的是这个稳定引用。 */
    val appUiState = MutableStateFlow(FeedUiState())

    /** 下一次 refresh 走失败路径（拦截器返 500），由 Feed._FailNext 命令置位。 */
    private val failNextRefresh = AtomicBoolean(false)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vmObserveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val json = Json { ignoreUnknownKeys = true }
        recorder = EventRecorder("${filesDir.absolutePath}/handeye/events.jsonl")

        val interceptor = HandeyeInterceptor(recorder) { page ->
            if (failNextRefresh.getAndSet(false)) {
                500 to """{"error":"boom"}"""
            } else if (page != 1) {
                200 to json.encodeToString(FeedPage.serializer(), FeedPage(emptyList()))
            } else {
                val items = (1..20).map { FeedItem(it, "Item $it") }
                200 to json.encodeToString(FeedPage.serializer(), FeedPage(items))
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        repository = FeedRepository(this, recorder, FeedApi(client))
        createViewModel()

        if (BuildConfig.DEBUG) {
            recorder.registerFlow(                kind = "state",
                nameKey = "name",
                serialize = { state ->
                    buildJsonObject {
                        put("name", "FeedUiState")
                        put("isLoading", state.isLoading)
                        put("listSize", state.items.size)
                        put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
                flow = { appUiState },
            )

            HandeyeInstaller.install(
                filesDir = filesDir.absolutePath,
                recorder = recorder,
                providers = listOf(
                    stateProvider("ui") {
                        val state = appUiState.value
                        buildJsonObject {
                            put("isLoading", state.isLoading)
                            put("listSize", state.items.size)
                            put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                            putJsonArray("likedIds") {
                                state.items.filter { it.liked }.forEach { add(JsonPrimitive(it.id)) }
                            }
                        }
                    },
                    stateProvider("memory") { repository.snapshotCache() },
                    stateProvider("persist") { repository.snapshotPersist() },
                    stateProvider("bootstrap") { BootstrapState.args.value },
                ),
                commands = CommandRegistry().apply {
                    register("Feed.Refresh") { viewModel.onRefresh() }
                    register("Item.ToggleLike") { args ->
                        args["id"]?.jsonPrimitive?.int?.let(viewModel::onToggleLike)
                    }
                    register("Feed._Reset") { viewModel.onClearAll() }
                    register("Feed._DropMemory") { viewModel.onDropMemoryCache() }
                    register("Feed._FailNext") { failNextRefresh.set(true) }
                    register("Feed._Recreate") { createViewModel() }
                },
                enabled = true,
            ).also { result ->
                when (result) {
                    is dev.handeye.device.InstallResult.Installed ->
                        Log.i("FeedApp", "handeye installed: port=${result.port}")
                    is dev.handeye.device.InstallResult.Failed ->
                        Log.e("FeedApp", "handeye install failed: ${result.message}", result.cause)
                    dev.handeye.device.InstallResult.Skipped ->
                        Log.w("FeedApp", "handeye install skipped")
                }
            }
        }
    }

    /** 重建 ViewModel 并把它的 uiState 转发进 [appUiState] —— 模拟进程内「冷启动」。 */
    private fun createViewModel() {
        vmObserveJob?.cancel()
        val vm = FeedViewModel(repository)
        viewModel = vm
        vmObserveJob = appScope.launch {
            vm.uiState.collect { appUiState.value = it }
        }
    }
}
