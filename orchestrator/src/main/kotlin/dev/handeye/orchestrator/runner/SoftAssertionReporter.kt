package dev.handeye.orchestrator.runner

/**
 * scenario 内每个断言点的执行记录 —— 用于 CLI 树状输出 & 汇总 pass/fail。
 *
 * 每个 [SoftAssertionStep] 是一行 ✓/✗，[failReason] 非空表示失败。
 */
data class SoftAssertionStep(
    val label: String,
    val pass: Boolean,
    val failReason: String? = null,
)

/**
 * 三源并列 soft assertion 汇总器。
 *
 * 不短路 —— 所有断言跑完后一起 report。任一 fail → scenario fail。
 */
class SoftAssertionReporter {
    private val steps = mutableListOf<SoftAssertionStep>()

    fun step(label: String, pass: Boolean, failReason: String? = null) {
        steps += SoftAssertionStep(label, pass, failReason)
    }

    fun allSteps(): List<SoftAssertionStep> = steps.toList()

    fun hasFailure(): Boolean = steps.any { !it.pass }

    /**
     * 树状格式化。`colored=true` 时给 `✗` 行 + failReason 染红（ANSI 转义），`✓` 行保持默认色。
     * TTY 检测由调用方（CLI 层）负责，本类不做——避免 test 也吃到 ANSI 转义污染 assertion。
     */
    fun formatTree(colored: Boolean = false): String = buildString {
        for (step in steps) {
            val mark = if (step.pass) "✓" else "✗"
            val line = "    $mark ${step.label}"
            appendLine(if (!step.pass && colored) AnsiColors.red(line) else line)
            if (!step.pass && step.failReason != null) {
                step.failReason.lineSequence().forEach { reasonLine ->
                    val indented = "        $reasonLine"
                    appendLine(if (colored) AnsiColors.red(indented) else indented)
                }
            }
        }
    }
}

/**
 * ANSI 颜色转义 —— CLI PASS/FAIL 可视化用。
 *
 * 使用契约：调用方（CLI 层）通过 [isTtyOutput] 决定是否染色，`false` 时输出无 ANSI 转义。
 * `NO_COLOR` 环境变量非空或 stdout 不是 TTY（重定向到文件 / 管道 / CI）时禁用染色，遵循
 * <https://no-color.org> 事实标准。
 */
object AnsiColors {
    private const val RESET = "[0m"
    private const val GREEN = "[32m"
    private const val RED = "[31m"

    /**
     * 是否应该输出 ANSI 转义 —— 遵循业界通用优先级：
     *
     * 1. `NO_COLOR` 环境变量非空 → 强制禁用（<https://no-color.org>）
     * 2. `FORCE_COLOR` 环境变量非空 → 强制启用（Gradle `:e2e-scenarios:run` 子 JVM 拿不到 TTY，需要
     *    shell 层显式传入；`e2e.sh` 检测 `[[ -t 1 ]]` 后把这个 env 传下来）
     * 3. `System.console() != null` → 直连 TTY 才启用
     *
     * `FORCE_COLOR` 是 Node.js / npm / prettier / vitest 等生态的事实标准，不用自造 env 名。
     */
    fun isTtyOutput(): Boolean {
        if (!System.getenv("NO_COLOR").isNullOrEmpty()) return false
        if (!System.getenv("FORCE_COLOR").isNullOrEmpty()) return true
        return System.console() != null
    }

    fun green(s: String): String = "$GREEN$s$RESET"
    fun red(s: String): String = "$RED$s$RESET"
}
