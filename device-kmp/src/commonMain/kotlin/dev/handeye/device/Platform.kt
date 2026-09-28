package dev.handeye.device

/** 平台委托：空闲端口 / 进程号 / 环境开关信号。 */
internal expect fun platformFreePort(): Int

internal expect fun platformProcessId(): Int

internal expect fun platformEnabledSignal(): String?
