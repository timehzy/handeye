package dev.handeye.device

import dev.handeye.device.endpoints.registerEndpoints
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * handeye 装配入口：宿主工程一行调用，注册端点 + 起内嵌 server。
 * 幂等——同一 CommandRegistry identity 重复 install 返回既有结果；
 * registry 变化先卸载再重装。release 变体 enabled 缺省判 false，零 socket 零订阅。
 */
object HandeyeInstaller {
    private const val EVENTS_REL = "handeye/events.jsonl"
    private val installLock = SynchronizedObject()

    @Volatile
    private var state: InstalledState? = null
    private var nextInstallId = 0L

    fun install(
        filesDir: String,
        recorder: EventRecorder,
        providers: List<StateProvider>,
        commands: CommandRegistry,
        server: HandeyeHttpServer? = null,
        enabled: Boolean? = null,
    ): InstallResult = synchronized(installLock) {
        state?.let { existing ->
            if (existing.commandsIdentity == commands.identity) {
                if (resolveEnabled(enabled) == false) {
                    uninstallLocked()
                    return@synchronized InstallResult.Skipped
                }
                return@synchronized InstallResult.Installed(
                    existing.port, existing.logFilePath, existing.installId,
                )
            }
            uninstallLocked()
        }
        if (resolveEnabled(enabled) != true) return@synchronized InstallResult.Skipped

        val logFilePath = "$filesDir/$EVENTS_REL"
        val actualServer = server ?: createPlatformServer()
        try {
            registerEndpoints(actualServer, recorder, providers, commands, logFilePath)
            val port = platformFreePort()
            actualServer.start(port)
            val installId = ++nextInstallId
            state = InstalledState(actualServer, port, logFilePath, commands.identity, installId)
            InstallResult.Installed(port, logFilePath, installId)
        } catch (t: Throwable) {
            runCatching { actualServer.stop() }
            InstallResult.Failed(t.message ?: "install failed", t)
        }
    }

    fun uninstall(installId: Long? = null) {
        synchronized(installLock) {
            val current = state ?: return
            if (installId != null && current.installId != installId) return
            uninstallLocked()
        }
    }

    private fun uninstallLocked() {
        val current = state ?: return
        runCatching { current.server.stop() }
        state = null
    }

    internal fun currentPort(): Int? = state?.port

    private fun resolveEnabled(explicit: Boolean?): Boolean? = when {
        explicit != null -> explicit
        else -> platformEnabledSignal()?.let { raw ->
            raw.isNotBlank() && raw != "0" && !raw.equals("false", ignoreCase = true)
        }
    }

    private data class InstalledState(
        val server: HandeyeHttpServer,
        val port: Int,
        val logFilePath: String,
        val commandsIdentity: Any?,
        val installId: Long,
    )
}

sealed class InstallResult {
    data class Installed(val port: Int, val logFilePath: String, val installId: Long) : InstallResult()
    data object Skipped : InstallResult()
    data class Failed(val message: String, val cause: Throwable? = null) : InstallResult()
}
