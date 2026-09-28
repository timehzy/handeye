package dev.handeye.device

/** release 变体不带 HTTP 引擎；enabled=false 时 Installer 在到达这里前已 Skipped。 */
internal actual fun createPlatformServer(): HandeyeHttpServer =
    throw IllegalStateException(
        "no HTTP server in release variant; pass server explicitly or use debug builds",
    )
