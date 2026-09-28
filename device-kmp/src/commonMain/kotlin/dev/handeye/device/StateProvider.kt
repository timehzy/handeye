package dev.handeye.device

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 命名快照源：GET /source?name=<name> 的响应提供方。snapshot 返 null = 源当前不可用，端点仍 200。 */
interface StateProvider {
    val name: String
    fun snapshot(): JsonElement?
}

/** StateProvider 装配：name + 快照函数。 */
fun stateProvider(name: String, snapshot: () -> JsonElement?): StateProvider =
    object : StateProvider {
        override val name: String = name
        override fun snapshot(): JsonElement? = snapshot()
    }
