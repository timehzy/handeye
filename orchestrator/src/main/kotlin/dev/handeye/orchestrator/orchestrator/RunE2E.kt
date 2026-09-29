package dev.handeye.orchestrator.orchestrator

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * 用户在 interactive 模式键入 `q` / `quit` 时由 [dev.handeye.orchestrator.orchestrator.ScenarioRunner]
 * 的 [dev.handeye.orchestrator.orchestrator.ScenarioRunner.Session.pause] 抛出——runner
 * 捕获后决定退出码。语义：用户主动退出，本身不算失败。
 *
 * 单独定义 exception 类型主要是让 runner 的 `catch (_: E2EQuitException)` 分支精确、可读，
 * 并让测试能 `assertFailsWith<E2EQuitException>` 精确断言。
 */
class E2EQuitException(message: String = "用户在 breakpoint 键入 quit") : RuntimeException(message)

/**
 * ThreadLocal 桥——[dev.handeye.orchestrator.orchestrator.ScenarioRunner] 用 `--interactive`
 * 起场景时，把 interactive/input/output 塞进这个 ThreadLocal；场景函数 / step helper
 * 不显式传 interactive 时自动继承。
 */
internal object InteractiveDefaults {
    data class Config(
        val interactive: Boolean,
        val input: BufferedReader,
        val output: PrintStream,
    )

    val current: ThreadLocal<Config?> = ThreadLocal.withInitial { null }
}
