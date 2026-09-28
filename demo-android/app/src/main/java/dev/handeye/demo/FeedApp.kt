package dev.handeye.demo

import android.app.Application
import dev.handeye.device.CommandRegistry
import dev.handeye.device.EventRecorder
import dev.handeye.device.HandeyeInstaller
import dev.handeye.device.stateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** demo 的 handeye L0 接线：事件流注册、三源快照、命令表。作为开源用户的接入模板。 */
class FeedApp : Application() {
    lateinit var recorder: EventRecorder
    lateinit var viewModel: FeedViewModel
    lateinit var repository: FeedRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val json = Json { ignoreUnknownKeys = true }
        recorder = EventRecorder("${filesDir.absolutePath}/handeye/events.jsonl")

        val interceptor = HandeyeInterceptor(recorder) { page ->
            if (page != 1) {
                200 to json.encodeToString(FeedPage.serializer(), FeedPage(emptyList()))
            } else {
                val items = (1..20).map { FeedItem(it, "Item $it") }
                200 to json.encodeToString(FeedPage.serializer(), FeedPage(items))
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        repository = FeedRepository(this, recorder, FeedApi(client))
        viewModel = FeedViewModel(repository)

        if (BuildConfig.DEBUG) {
            recorder.registerFlow(
                kind = "state",
                nameKey = "name",
                serialize = { state ->
                    buildJsonObject {
                        put("name", "FeedUiState")
                        put("isLoading", state.isLoading)
                        put("listSize", state.items.size)
                        put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
                flow = { viewModel.uiState },
            )

            HandeyeInstaller.install(
                filesDir = filesDir.absolutePath,
                recorder = recorder,
                providers = listOf(
                    stateProvider("ui") {
                        val state = viewModel.uiState.value
                        buildJsonObject {
                            put("isLoading", state.isLoading)
                            put("listSize", state.items.size)
                            put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    },
                    stateProvider("memory") { repository.snapshotCache() },
                    stateProvider("persist") { repository.snapshotPersist() },
                ),
                commands = CommandRegistry().apply {
                    register("Feed.Refresh") { viewModel.onRefresh() }
                    register("Item.ToggleLike") { args ->
                        args["id"]?.jsonPrimitive?.int?.let(viewModel::onToggleLike)
                    }
                    register("Feed._Reset") { viewModel.onClearAll() }
                    register("Feed._DropMemory") { viewModel.onDropMemoryCache() }
                },
                enabled = true,
            )
        }
    }
}
