package dev.handeye.device

import kotlinx.serialization.json.Json

/** 协议 JSON 配置的单一事实源。 */
val HandeyeJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
