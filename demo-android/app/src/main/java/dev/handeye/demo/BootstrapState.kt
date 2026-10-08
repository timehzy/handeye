package dev.handeye.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** bootstrap deeplink 参数的最新快照：MainActivity 落值，FeedApp 注册为 bootstrap 命名源。 */
object BootstrapState {
    val args = MutableStateFlow<JsonObject?>(null)
}
