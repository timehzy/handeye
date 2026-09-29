package dev.handeye.device

/** 平台委托：空闲端口 / 进程号 / 环境开关信号 / 默认 HTTP server 工厂。 */
internal expect fun platformFreePort(): Int

internal expect fun platformProcessId(): Int

internal expect fun platformEnabledSignal(): String?

/** 平台默认 server；iOS 未注入实现时抛异常，调用方应经 install 的 server 参数注入。 */
internal expect fun createPlatformServer(): HandeyeHttpServer
