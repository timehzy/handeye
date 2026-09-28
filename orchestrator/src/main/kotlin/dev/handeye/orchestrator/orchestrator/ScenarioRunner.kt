package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.context.RunRecord
import dev.handeye.orchestrator.events.EventsTailer
import dev.handeye.orchestrator.events.HttpEventsFetcher
import dev.handeye.orchestrator.http.OrchestratorHttpClient
import dev.handeye.orchestrator.runner.AnsiColors
import dev.handeye.orchestrator.runner.ScenarioResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess

/**
 * 场景执行入口 —— `./gradlew :e2e-scenarios:run --args="<...>"` 调用（application 插件生成的 :run task）。
 * 也是 e2e.sh 统一入口最终 fork 的 process。
 *
 * ## 调用形式
 *
 * 1. **单场景**：
 *    ```
 *    ScenarioRunner <scenario> <baseUrl>
 *    ```
 *
 * 2. **按 tag 过滤**：
 *    ```
 *    ScenarioRunner --tag <tag> <baseUrl>
 *    ```
 *    按注入 registry 的定义顺序，跑所有命中 tag 的场景；全部通过 exit 0，任一失败 exit 1。
 *
 * 3. **全部场景**：
 *    ```
 *    ScenarioRunner --all <baseUrl>
 *    ```
 *    按注入 registry 的定义顺序跑全部场景，退出码语义同 `--tag`。
 *
 * 3b. **逗号分隔多场景**（e2e.sh per-scenario bootstrap 分组跑批用）：
 *    ```
 *    ScenarioRunner <name1,name2,...> <baseUrl>
 *    ```
 *    按传入顺序跑这一组场景，退出码语义同 `--tag`；任一名字未注册 → exit 2 不跑部分场景。
 *
 * 3c. **bootstrap plan 子命令**（无 baseUrl，只读 catalog 元数据）：
 *    ```
 *    ScenarioRunner --print-bootstrap-plan <scenario|--tag tag|--all>
 *    ```
 *    每行输出 `<name>\t<page>\t<fixture>`，供 e2e.sh 分组 bootstrap 消费。
 *    契约详见 [BootstrapPlanCli]。
 *
 * 4. **交互式**（可与上面三种形式叠加）：
 *    ```
 *    ScenarioRunner --interactive <scenario|--tag tag> <baseUrl>
 *    ScenarioRunner <scenario|--tag tag> <baseUrl> --interactive
 *    ```
 *    加 `--interactive`（或短名 `-i`）后：
 *    - 每个场景开始前打印 `[BREAK] before <name>` prompt，回车继续、`q` 退出
 *    - 每个场景结束后打印 `[BREAK] after <name> (PASS/FAIL)` prompt，回车继续下一个
 *    非交互模式（不传 flag）下场景之间不打断。
 *
 * 5. **实时打印 events**（可与前面所有形式叠加）：
 *    ```
 *    ScenarioRunner --tail-events <scenario|--tag tag|--all> <baseUrl>
 *    ```
 *    加 `--tail-events` 后：orchestrator 在场景开跑前 launch 一个后台协程轮询
 *    device 端 events endpoint（默认 200ms 周期），每条新 event 立刻打一行 `[tail t=... ] ...`
 *    到 stdout。场景结束（PASS/FAIL/QUIT）后自动 stop tailer。
 *
 * ## 输出格式
 *
 * 每个场景跑完打两段：
 * ```
 * [PASS|FAIL] <name> (<elapsedMs>ms)
 *     ✓ step-a
 *     ✗ step-b
 *         expected=X, actual=Y
 * ```
 * 结尾汇总 `Summary: X/Y passed`；退出码由 [computeExitCode] 计算——全部 PASS 返 0，任一
 * FAIL 返 1；用法错误（参数缺失 / 未知场景 / 已下线 flag）返 2。
 */
fun ScenarioRunner.cli(args: Array<String>) {
    // `snapshot` 子命令走独立 CLI，不占用 orchestrator 参数解析。放在 parseArgs 前
    // 避免被 printUsageAndExit 拒绝。约定：`snapshot diff <a> <b>`——现有仅此一个子命令。
    if (args.isNotEmpty() && args[0] == "snapshot") {
        exitProcess(SnapshotDiffCli.run(args.drop(1)))
    }
    // `--print-bootstrap-plan` 子命令：只读 catalog 元数据输出 scenario → page/fixture 映射，
    // 供 e2e.sh per-scenario bootstrap 消费。同 snapshot 一样在 parseArgs 前分派（无 baseUrl）。
    if (args.isNotEmpty() && args[0] == BootstrapPlanCli.FLAG) {
        exitProcess(BootstrapPlanCli.run(args.drop(1), registry))
    }
    // 早期检测已下线 flag：`--record` / `--force`。给旧脚本一个明确的迁移提示，避免它们
    // 被当成静默 PASS 跑过。参数解析层不再识别这两个 flag。
    if (args.any { it == "--record" || it == "--force" }) {
        System.err.println(
            "[DEPRECATED] --record / --force 已下线：" +
                "场景断言由 hostVmTest expectFact / expectDependencies 直接完成。" +
                "请更新调用脚本移除这两个 flag。",
        )
        exitProcess(2)
    }
    val parsed = parseArgs(args) ?: run { printUsageAndExit(registry) }
    when (parsed) {
        is ParsedArgs.Single -> runSingle(parsed, registry)
        is ParsedArgs.Tag -> runByTag(parsed, registry)
        is ParsedArgs.All -> runAll(parsed, registry)
        is ParsedArgs.Scenarios -> runScenarios(parsed, registry)
    }
}

/**
 * 可长驻的场景 runner —— 支持单次 [runOne] 与流式 [runStream]。
 *
 * 当前 CLI 入口仍走文件顶级的 `fun main`，保持 application 插件主类不变。
 */
class ScenarioRunner(
    internal val registry: ScenarioRegistry,
) {

    /**
     * 跑单个场景并返回 [RunRecord]。
     *
     * 若 [name] 在 [registry] 中未注册，或 context 未提供 scenario target，返回 failed
     * [RunRecord]。注册场景的异常也收敛为 failed record，保持长驻调用方的流不中断。
     */
    fun runOne(ctx: E2eContext, name: String): RunRecord {
        val startedAtMs = System.currentTimeMillis()
        val meta = registry.findByName(name)
            ?: return RunRecord(
                scenarioName = name,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                passed = false,
                failedSoft = 1,
            )
        val target = ctx.diagnostics?.target
            ?: return RunRecord(
                scenarioName = name,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                passed = false,
                failedSoft = 1,
            )
        return try {
            val result = meta.run(target)
            RunRecord(
                scenarioName = result.name,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                passed = result.pass,
                failedSoft = result.reporter.allSteps().count { !it.pass },
            )
        } catch (_: Throwable) {
            RunRecord(
                scenarioName = name,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                passed = false,
                failedSoft = 1,
            )
        }
    }

    /** 以冷流形式跑一次 [runOne]， emit 单条 [RunRecord]。 */
    fun runStream(ctx: E2eContext, name: String): Flow<RunRecord> = flow {
        emit(runOne(ctx, name))
    }
}

/**
 * 参数解析——把 [--interactive|-i] / [--tail-events] 拆出来后再按 `--tag <tag> <baseUrl>` 或
 * `<scenario> <baseUrl>` 拆定位参数。flag 可以出现在任意位置，`--`（等长两字符）不视为
 * flag（gradle `--args` 里如果透传 `--` 分隔符会踩坑，本 runner 不额外支持）。
 *
 * 返回 null 表示参数不合法，由 [main] 走 [printUsageAndExit]。
 *
 * `internal` 可见：仅为单测能直接读解析结果，不作为对外 API。
 */
internal fun parseArgs(args: Array<String>): ParsedArgs? {
    var interactive = false
    var tailEvents = false
    val positional = mutableListOf<String>()
    for (a in args) {
        when (a) {
            "--interactive", "-i" -> interactive = true
            "--tail-events" -> tailEvents = true
            else -> positional += a
        }
    }
    return when {
        positional.size >= 3 && positional[0] == "--tag" ->
            ParsedArgs.Tag(
                tag = positional[1],
                baseUrl = positional[2],
                interactive = interactive,
                tailEvents = tailEvents,
            )
        positional.size == 2 && positional[0] == "--all" ->
            ParsedArgs.All(
                baseUrl = positional[1],
                interactive = interactive,
                tailEvents = tailEvents,
            )
        positional.size == 2 && positional[0] != "--tag" && positional[0] != "--all" -> {
            // 逗号分隔多场景形态（e2e.sh per-scenario bootstrap 分组跑批用）：
            // `ScenarioRunner name1,name2,... <baseUrl>`。单名（不含逗号）保持 Single 语义。
            val names = positional[0].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (names.size > 1) {
                ParsedArgs.Scenarios(
                    names = names,
                    baseUrl = positional[1],
                    interactive = interactive,
                    tailEvents = tailEvents,
                )
            } else {
                // 用规整后的 names[0] 而不是 positional[0]——尾逗号形态（"select_ratio,"）
                // 规整后只剩一名，归 Single 语义时不能把逗号带进 scenario name
                ParsedArgs.Single(
                    scenario = names.firstOrNull() ?: positional[0],
                    baseUrl = positional[1],
                    interactive = interactive,
                    tailEvents = tailEvents,
                )
            }
        }
        else -> null
    }
}

/**
 * `internal` 可见：仅为单测能直接构造/断言，不作为对外 API。
 */
internal sealed interface ParsedArgs {
    val baseUrl: String
    val interactive: Boolean
    val tailEvents: Boolean
    data class Single(
        val scenario: String,
        override val baseUrl: String,
        override val interactive: Boolean,
        override val tailEvents: Boolean = false,
    ) : ParsedArgs
    data class Tag(
        val tag: String,
        override val baseUrl: String,
        override val interactive: Boolean,
        override val tailEvents: Boolean = false,
    ) : ParsedArgs
    data class All(
        override val baseUrl: String,
        override val interactive: Boolean,
        override val tailEvents: Boolean = false,
    ) : ParsedArgs
    /**
     * 逗号分隔多场景形态——e2e.sh per-scenario bootstrap 按 (page, fixture) 分组后，
     * 一组内多个 scenario 用一次 JVM 调用跑完（避免每场景一次 gradle 启动开销）。
     * 顺序 = 传入顺序（e2e.sh 保持 catalog 相对顺序）。
     */
    data class Scenarios(
        val names: List<String>,
        override val baseUrl: String,
        override val interactive: Boolean,
        override val tailEvents: Boolean = false,
    ) : ParsedArgs
}

private fun runSingle(parsed: ParsedArgs.Single, registry: ScenarioRegistry) {
    val meta = registry.findByName(parsed.scenario) ?: run {
        System.err.println("未知场景: ${parsed.scenario}")
        System.err.println("可选场景: ${registry.all().joinToString { it.name }}")
        exitProcess(2)
    }
    val session = if (parsed.interactive) InteractiveSession.stdio() else InteractiveSession.disabled()
    var outcome: RunOutcome = RunOutcome.PASS
    val collected = mutableListOf<ScenarioResult>()
    withEventsTailer(parsed.baseUrl, enabled = parsed.tailEvents) {
        outcome = runOne(
            meta, parsed.baseUrl, session,
            onResult = { r ->
                collected += r
                printResult(r)  // 每个场景跑完立即打，全部 stdout 单流保证时序
            },
        )
    }
    printFinalSummary(collected)
    // QUIT 前若场景本身 FAIL 由 runOne 内部处理（FAIL 后跳过 after prompt，避免 q 覆盖）。
    // 单场景形态下 QUIT = 用户在 before/after 主动退出——ScenarioResult 若已产出则参与汇总，
    // 否则视为「没跑」直接 exit 0。
    if (outcome == RunOutcome.QUIT && collected.none { !it.pass }) exitProcess(0)
    exitProcess(computeExitCode(collected))
}

private fun runByTag(parsed: ParsedArgs.Tag, registry: ScenarioRegistry) {
    val matched = registry.filterByTag(parsed.tag)
    if (matched.isEmpty()) {
        System.err.println("tag \"${parsed.tag}\" 未命中任何场景")
        System.err.println("已注册 tag: ${registry.allTags().joinToString()}")
        exitProcess(2)
    }
    runBatch(
        matched,
        parsed.baseUrl,
        parsed.interactive,
        parsed.tailEvents,
        header = "按 tag \"${parsed.tag}\" 跑 ${matched.size} 个场景: ${matched.joinToString { it.name }}",
    )
}

private fun runAll(parsed: ParsedArgs.All, registry: ScenarioRegistry) {
    val all = registry.all()
    runBatch(
        all,
        parsed.baseUrl,
        parsed.interactive,
        parsed.tailEvents,
        header = "跑全部 ${all.size} 个场景: ${all.joinToString { it.name }}",
    )
}

/**
 * 逗号分隔多场景形态——按 names 顺序 resolve + 跑批。任一名字未注册 → 列出全部未知名后
 * exit 2（不跑部分场景：e2e.sh 分组语义要求整组原子成功/失败，否则分组报告对不齐）。
 */
private fun runScenarios(parsed: ParsedArgs.Scenarios, registry: ScenarioRegistry) {
    val metas = mutableListOf<ScenarioMeta>()
    val unknown = mutableListOf<String>()
    for (name in parsed.names) {
        val meta = registry.findByName(name)
        if (meta == null) unknown += name else metas += meta
    }
    if (unknown.isNotEmpty()) {
        System.err.println("未知场景: ${unknown.joinToString()}")
        System.err.println("可选场景: ${registry.all().joinToString { it.name }}")
        exitProcess(2)
    }
    runBatch(
        metas,
        parsed.baseUrl,
        parsed.interactive,
        parsed.tailEvents,
        header = "跑 ${metas.size} 个场景: ${metas.joinToString { it.name }}",
    )
}

/**
 * 批量跑一组场景——`--tag` / `--all` 共用。按输入顺序独立 reset，QUIT 早退时仍以 FAIL 优先出退出码。
 */
private fun runBatch(
    scenarios: List<ScenarioMeta>,
    baseUrl: String,
    interactive: Boolean,
    tailEvents: Boolean,
    header: String,
) {
    println("==> $header")
    val session = if (interactive) InteractiveSession.stdio() else InteractiveSession.disabled()
    val collected = mutableListOf<ScenarioResult>()
    var quitEarly = false
    withEventsTailer(baseUrl, enabled = tailEvents) {
        for (meta in scenarios) {
            val outcome = runOne(
                meta, baseUrl, session,
                onResult = { r ->
                    collected += r
                    printResult(r)  // 每个场景跑完立即打，全部 stdout 单流保证时序
                },
            )
            if (outcome == RunOutcome.QUIT) {
                val remaining = scenarios.size - scenarios.indexOf(meta) - 1
                println("==> 用户在 breakpoint 主动退出，剩余 $remaining 个场景未跑")
                quitEarly = true
                break
            }
        }
    }
    printFinalSummary(collected)
    val exitCode = computeExitCode(collected)
    if (quitEarly && exitCode == 1) {
        // quit 不能掩盖 FAIL：quit 前若已累积失败，仍以 exit 1 收尾
        println(
            colorize("[FAIL] 注意：quit 前已有失败场景——quit 不掩盖 FAIL，退出码 1", pass = false),
        )
    }
    exitProcess(exitCode)
}

/**
 * 单场景 report 实时打印——runOne 拿到 [ScenarioResult] 时立即调用。
 *
 * ## 为什么单流 + 上色而不是 stdout/stderr 分流
 *
 * 之前 PASS 走 stdout / FAIL 走 stderr，terminal 上两条流各自缓冲、渲染顺序不保证：
 * `==> 场景 apply_beauty 开始`（stdout）+ `[FAIL 现场保留]`（stderr）+ 后续 `[PASS] xxx tree`
 * （stdout）会交错乱序，看着像"tree 归属错了场景"。改成**全部 stdout 单流**天然按调用顺序打
 * 出；PASS/FAIL 靠 ANSI 颜色（绿/红）区分，颜色由 [AnsiColors.isTtyOutput] 决定（TTY 且
 * 未设 NO_COLOR 才染，重定向 / CI 自动关）。
 */
internal fun printResult(r: ScenarioResult) {
    val mark = if (r.pass) "PASS" else "FAIL"
    val header = "[$mark] ${r.name} (${r.elapsedMs}ms)"
    println(colorize(header, pass = r.pass))
    val tree = r.reporter.formatTree(colored = AnsiColors.isTtyOutput())
    if (tree.isNotEmpty()) {
        // formatTree 已经带前导空格与末尾换行；直接原样打印，避免重复缩进
        print(tree)
    }
    System.out.flush()
}

/** 末尾一行 `==> Summary: X/Y passed`——全部场景打完统一收尾。 */
private fun printFinalSummary(results: List<ScenarioResult>) {
    val passed = results.count { it.pass }
    val total = results.size
    val allPass = results.all { it.pass }
    val line = "==> Summary: $passed/$total passed"
    // 空列表（QUIT 前没跑任何场景）不上色，用默认色以免误导
    println(if (results.isEmpty()) line else colorize(line, pass = allPass))
    System.out.flush()
}

/** ANSI 上色 helper——`pass=true` 绿 / `false` 红；非 TTY 原样返。 */
private fun colorize(text: String, pass: Boolean): String {
    if (!AnsiColors.isTtyOutput()) return text
    return if (pass) AnsiColors.green(text) else AnsiColors.red(text)
}

/**
 * 计算 CLI 退出码。
 *
 * - 0：`results` 全部 pass（含空列表——什么都没跑就当作没失败，配合 `--interactive` 用户在
 *   before prompt 直接键入 q 的场景）
 * - 1：至少一条 FAIL
 *
 * 用法错误（参数缺失 / 未知场景 / 已下线 flag）由调用方在参数解析阶段直接
 * `exitProcess(2)`，不进入本函数。
 *
 * `internal` 可见：单测直接构造 [ScenarioResult] 列表校验退出码语义。
 */
internal fun computeExitCode(results: List<ScenarioResult>): Int =
    if (results.all { it.pass }) 0 else 1

/**
 * 跑单个场景。返回三态：PASS / FAIL / QUIT（用户主动退出）——上层根据 QUIT 决定是否立刻
 * exit、跳过后续场景。异常已在此消化并打印，不再上抛。
 *
 * ## 三态与 ScenarioResult 的关系
 *
 * scenario 函数统一返 [ScenarioResult]（不靠抛异常）。runOne 三态定义如下：
 * - PASS：scenario 返回 [ScenarioResult] 且 `result.pass = true` → onResult 上报 + return PASS
 * - FAIL：scenario 返回 `result.pass = false`，或抛未预期异常（IO/timeout 等）→ 打印现场
 *   保留 + onResult 上报（异常路径合成一个 name=meta.name 的失败 [ScenarioResult]）+ return FAIL
 * - QUIT：before prompt 用户键入 q（未跑场景，不上报）；或 after prompt 键入 q（PASS 已上报）
 *
 * ## FAIL 后不再问 after prompt
 *
 * 场景 FAIL 后如果还问「after prompt」，用户键入 q 会覆盖成 QUIT，把 FAIL 信息掩掉——
 * 上层看到 QUIT 就 exit(0)，CI 就漏报了。所以 FAIL 后跳过 after prompt，让 FAIL 冒泡到调用方。
 * PASS 后正常问，保留「跑完看一眼再继续」的调试节奏。
 *
 * ## FAIL 现场保留
 *
 * FAIL 时 orchestrator **不**主动 kill App；只打印通用 target 信息。原始诊断由 scenario 的
 * [E2eContext] 提供并由 hostVmTest 落盘，orchestrator 不解释 host 控制面或 artifact 类型。
 *
 * @param onFail FAIL 时被调，接收 baseUrl → 打印现场保留信息。生产走
 *   [defaultFailContextPrinter]；单测传 no-op 或 spy 断言调用即可。
 * @param onResult 每次场景产生 [ScenarioResult]（PASS 或 FAIL 均有）时上报给调用方，供
 *   汇总打印 + 退出码计算；QUIT（before/after prompt 主动退出、场景未跑或已 PASS 上报过）
 *   不重复调用。
 *
 * `internal` 可见：仅为单测直接测三态转移。
 */
internal fun runOne(
    meta: ScenarioMeta,
    baseUrl: String,
    session: InteractiveSession,
    onFail: (String) -> Unit = ::defaultFailContextPrinter,
    onResult: (ScenarioResult) -> Unit = {},
): RunOutcome {
    // interactive 下先把场景元信息打出来再问 before 断点——让用户在停下时就能看到「下一个跑什么、tag 是什么」
    // 而不是回车后再看到 header。非交互模式合并成单行 log。
    if (session.enabled) {
        println("==> 场景 ${meta.name} 待跑 (baseUrl=$baseUrl, tags=${meta.tags}, interactive=true)")
    }

    // 场景之间断点：开始前
    try {
        session.pause("before ${meta.name}")
    } catch (_: E2EQuitException) {
        return RunOutcome.QUIT
    }

    val outcome = try {
        if (!session.enabled) {
            println("==> 场景 ${meta.name} 开始 (baseUrl=$baseUrl, tags=${meta.tags})")
        } else {
            println("==> 场景 ${meta.name} 开始执行")
        }
        val result = session.runScenario(meta, baseUrl)
        onResult(result)
        if (result.pass) {
            RunOutcome.PASS
        } else {
            // scenario 返回 pass=false（reporter 里有失败 step）——现场保留 + 归结为 FAIL。
            onFail(baseUrl)
            RunOutcome.FAIL
        }
    } catch (_: E2EQuitException) {
        println("[QUIT] 场景 ${meta.name} 被用户主动退出")
        RunOutcome.QUIT
    } catch (t: Throwable) {
        // hostVmTest 内部已经把大部分异常写进 reporter，这里的兜底路径主要覆盖：runner 自身
        // 的执行异常（例如 session.runScenario 的 ThreadLocal 设置失败）、旧兼容代码抛的
        // AssertionError 等。合成一个失败 [ScenarioResult]，让汇总链路语义闭合。
        // 走 stdout 保证与其它 [PASS]/[FAIL] 单流顺序；stack trace 仍打 stderr（不需要顺序对齐）
        println(colorize("[FAIL] 场景 ${meta.name} 失败: ${t.javaClass.simpleName}: ${t.message}", pass = false))
        t.printStackTrace(System.err)
        onFail(baseUrl)
        onResult(
            ScenarioResult(
                name = meta.name,
                pass = false,
                reporter = dev.handeye.orchestrator.runner.SoftAssertionReporter().apply {
                    step(
                        label = "scenario",
                        pass = false,
                        failReason = "${t.javaClass.simpleName}: ${t.message}",
                    )
                },
                elapsedMs = 0L,
            ),
        )
        RunOutcome.FAIL
    }

    // 场景之间断点：结束后。QUIT 已经决定退出、FAIL 不能被 quit 掩盖，都不再问 after prompt。
    if (outcome == RunOutcome.PASS) {
        try {
            session.pause("after ${meta.name} (${outcome.name})")
        } catch (_: E2EQuitException) {
            return RunOutcome.QUIT
        }
    }
    return outcome
}

/** 默认「FAIL 现场保留」打印器。Host-specific 诊断由 [E2eContext.diagnostics] 负责。 */
internal fun defaultFailContextPrinter(baseUrl: String) {
    // 走 stdout 保证与 [PASS]/[FAIL] header 单流顺序；`[FAIL 现场保留]` 字面保留便于 grep
    println(
        colorize(
            "[FAIL 现场保留] target=$baseUrl — 未 kill App；原始诊断由 scenario context 采集",
            pass = false,
        ),
    )
    System.out.flush()
}

internal enum class RunOutcome { PASS, FAIL, QUIT }

/**
 * 交互会话——把「跑场景」和「场景之间断点」封在一起，方便测试注入 stdin/stdout mock。
 *
 * scenario 函数返回 [ScenarioResult]（统一签名）；interactive 模式下把 stdin/stdout
 * 通过 [InteractiveDefaults] ThreadLocal 透传给 scenario 函数 / step helper 使用。
 *
 * `internal` 可见：仅为单测能构造 spy / mock 版本，注入到 [runOne] 断言 pause/runScenario 交互。
 */
internal open class InteractiveSession(
    val enabled: Boolean,
    private val input: BufferedReader,
    private val output: PrintStream,
) {
    /** 场景之间断点。 */
    open fun pause(label: String) {
        if (!enabled) return
        while (true) {
            output.println("[BREAK] $label — 按回车继续，'q' 退出")
            output.flush()
            val line = input.readLine() ?: return
            when (line.trim().lowercase()) {
                "", "c", "continue" -> return
                "q", "quit", "exit" -> throw E2EQuitException("用户在 \"$label\" 键入 $line")
                else -> output.println("[BREAK] 未识别: $line （回车继续 / q 退出）")
            }
        }
    }

    /**
     * 执行场景函数——通过 [InteractiveDefaults] ThreadLocal 把 interactive/input/output 传给
     * 场景内部使用。场景函数返回 [ScenarioResult]。
     */
    open fun runScenario(meta: ScenarioMeta, baseUrl: String): ScenarioResult {
        if (!enabled) {
            return meta.run(baseUrl)
        }
        val prev = InteractiveDefaults.current.get()
        InteractiveDefaults.current.set(InteractiveDefaults.Config(interactive = true, input = input, output = output))
        try {
            return meta.run(baseUrl)
        } finally {
            if (prev == null) InteractiveDefaults.current.remove() else InteractiveDefaults.current.set(prev)
        }
    }

    companion object {
        fun stdio(): InteractiveSession =
            InteractiveSession(true, BufferedReader(InputStreamReader(System.`in`)), System.out)

        fun disabled(): InteractiveSession =
            InteractiveSession(false, BufferedReader(InputStreamReader(System.`in`)), System.out)
    }
}

/**
 * `--tail-events` 生命周期包装器——[enabled] = true 时在 [Dispatchers.IO] 上 launch tailer
 * 后调 [block]（同步执行的 scenario runner 主体）；block 返回后 cancel scope 释放 tailer。
 *
 * 实现上通过 [EventsTailer] + [HttpEventsFetcher] 拉事件，打印格式为通用
 * `[tail seq=<seq> t=<t>] kind=<kind> raw=<json>`，不再硬编码任何 kind 前缀或 label 字段。
 *
 * 为什么不用 `runBlocking { launch(...); block() }`：现有 [runSingle] / [runByTag] 内部调
 * [kotlin.system.exitProcess]——runBlocking 的 finally 永远不会跑，tailer 靠 JVM 退出被杀。
 * 用独立 [CoroutineScope] 就是明确表示「tailer 生命周期挂在 IO scope 上」，退出路径无论走
 * 正常 return 还是 exitProcess 都能收敛：
 * - 正常 return（本函数 finally 命中） → 显式 cancel scope
 * - exitProcess（block 里 exitProcess）→ JVM 全进程死亡，daemon/non-daemon 一起收
 *
 * @param baseUrl orchestrator 连接的 debug server；内部 new [OrchestratorHttpClient]
 * @param enabled false 时 no-op 直通 [block]，零开销
 */
internal fun withEventsTailer(baseUrl: String, enabled: Boolean, block: () -> Unit) {
    if (!enabled) {
        block()
        return
    }
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val tailer = EventsTailer(HttpEventsFetcher(OrchestratorHttpClient(baseUrl)))
    val job: Job = scope.launch {
        while (true) {
            coroutineContext.ensureActive()
            try {
                for (ev in tailer.pollLatest(limit = 1000)) {
                    println("[tail seq=${ev.seq} t=${ev.timeMs}] kind=${ev.kind} raw=${ev.raw}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                println("[TAIL WARN] events fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            delay(200L)
        }
    }
    println("==> --tail-events 已开启（轮询 device 端 events，每 200ms 一次）")
    try {
        block()
    } finally {
        job.cancel()
        scope.cancel()
    }
}

private fun printUsageAndExit(registry: ScenarioRegistry): Nothing {
    System.err.println("用法:")
    System.err.println("  ScenarioRunner [--interactive|-i] [--tail-events] <scenario> <baseUrl>")
    System.err.println("  ScenarioRunner [--interactive|-i] [--tail-events] <name1,name2,...> <baseUrl>")
    System.err.println("  ScenarioRunner [--interactive|-i] [--tail-events] --tag <tag> <baseUrl>")
    System.err.println("  ScenarioRunner [--interactive|-i] [--tail-events] --all <baseUrl>")
    System.err.println("  ScenarioRunner ${BootstrapPlanCli.FLAG} <scenario|--tag tag|--all>")
    System.err.println("  ScenarioRunner snapshot diff <a.json> <b.json>")
    System.err.println("现有场景: ${registry.all().joinToString { it.name }}")
    System.err.println("已注册 tag: ${registry.allTags().joinToString()}")
    exitProcess(2)
}

private fun ScenarioRegistry.allTags(): Set<String> = all().flatMap { it.tags }.toSortedSet()
