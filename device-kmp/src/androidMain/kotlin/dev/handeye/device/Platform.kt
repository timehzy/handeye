package dev.handeye.device

import java.net.ServerSocket

internal actual fun platformFreePort(): Int =
    ServerSocket(0).use { it.localPort }

internal actual fun platformProcessId(): Int = android.os.Process.myPid()

internal actual fun platformEnabledSignal(): String? {
    // android.os.SystemProperties 是隐藏 API，公开 SDK 编不过；反射读取，失败时退回环境变量。
    val viaProperties = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java)
            .invoke(null, "debug.handeye.enabled") as? String
    }.getOrNull()
    return viaProperties?.ifBlank { null } ?: System.getenv("HANDEYE_ENABLED")
}
