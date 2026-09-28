package dev.handeye.device

import kotlinx.serialization.json.JsonObject

/** POST /cmd 的命令表：稳定字符串 key → 处理器。 */
class CommandRegistry {
    private val handlers = LinkedHashMap<String, (JsonObject) -> Unit>()

    /** 内容身份：register 后变化，供 Installer 判定是否需要重装。 */
    val identity: Any
        get() = handlers.keys.toList()

    fun register(key: String, handler: (JsonObject) -> Unit) {
        handlers[key] = handler
    }

    fun dispatch(key: String, args: JsonObject) {
        val handler = handlers[key]
            ?: throw IllegalArgumentException("unknown command: $key")
        handler(args)
    }
}
