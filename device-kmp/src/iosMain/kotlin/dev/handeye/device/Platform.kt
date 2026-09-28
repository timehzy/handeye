package dev.handeye.device

import platform.Foundation.NSProcessInfo

internal actual fun platformFreePort(): Int = 27778

internal actual fun platformProcessId(): Int =
    NSProcessInfo.processInfo.processIdentifier.toInt()

internal actual fun platformEnabledSignal(): String? =
    NSProcessInfo.processInfo.environment["HANDEYE_ENABLED"] as? String
