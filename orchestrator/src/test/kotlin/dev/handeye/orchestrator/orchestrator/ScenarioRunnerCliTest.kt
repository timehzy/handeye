package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.runner.ScenarioResult
import dev.handeye.orchestrator.runner.SoftAssertionReporter
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ScenarioRunner CLI 层单测——针对 [ScenarioRunner.kt] 的 `parseArgs` + `runOne` +
 * [defaultFailContextPrinter] + [computeExitCode]。
 *
 * ## 覆盖
 *
 * 1. **parseArgs**：flag 位置自由（前/后/中间）、`-i` 短名、组合形态（`--interactive` + `--tag`
 *    / 单场景 + baseUrl）、非法参数返回 null。`--record` / `--force` 已下线，parseArgs
 *    不再识别（走 main 早期弃用提示分支）。
 * 2. **runOne 三态**：PASS（scenario 返回 pass=true 的 ScenarioResult）→ RunOutcome.PASS；FAIL
 *    （scenario 返回 pass=false，或抛 AssertionError / RuntimeException）→ RunOutcome.FAIL 且
 *    onFail 被调；QUIT（scenario 抛 [E2EQuitException]）→ RunOutcome.QUIT 且 onFail 不调。
 * 3. **runOne interactive 交互**：session.enabled=true 时 before/after prompt 都被调 pause——
 *    PASS 场景两次 pause，FAIL 场景只 before 一次 pause（after 跳过，避免 quit 掩盖 FAIL）。
 * 4. **defaultFailContextPrinter**：只打印通用 target；host-specific raw diagnostics 由
 *    E2eContext 提供，不在 orchestrator 解释。
 * 5. **computeExitCode**（E2 新增）：全部 pass → 0；任一 fail → 1；空列表 → 0。
 *
 * ## 为什么这些函数改成 `internal`
 *
 * 原本 `parseArgs` / `runOne` / `InteractiveSession` / `ParsedArgs` / `RunOutcome` /
 * `computeExitCode` 都是 `private`。单测想直接构造 CLI 参数、断言解析结果、注入 spy session
 * 断言交互调用，需要放开可见性——改成 `internal` 是 pragmatic 妥协：模块内可见、对外仍不算 API。
 * 相比通过 `main(args)` 走 `exitProcess` 的 e2e 测试模式，internal 直接测更快也更可预期。
 */
class ScenarioRunnerCliTest {

    @BeforeTest
    fun setUp() {
        InteractiveDefaults.current.remove()
    }

    @AfterTest
    fun tearDown() {
        InteractiveDefaults.current.remove()
    }

    // ---- parseArgs：位置自由 + 组合 ----

    @Test
    fun `parseArgs --interactive 在前 单场景`() {
        val parsed = parseArgs(arrayOf("--interactive", "select_ratio", "http://127.0.0.1:47301"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertEquals("select_ratio", single.scenario)
        assertEquals("http://127.0.0.1:47301", single.baseUrl)
        assertTrue(single.interactive)
    }

    @Test
    fun `parseArgs --interactive 在后 单场景`() {
        val parsed = parseArgs(arrayOf("select_ratio", "http://127.0.0.1:47301", "--interactive"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertEquals("select_ratio", single.scenario)
        assertEquals("http://127.0.0.1:47301", single.baseUrl)
        assertTrue(single.interactive)
    }

    @Test
    fun `parseArgs --interactive 在中间 tag 形态`() {
        // --tag <tag> <baseUrl> 定位参数不动，flag 塞中间
        val parsed = parseArgs(arrayOf("--tag", "--interactive", "smoke", "http://127.0.0.1:47301"))
        val tag = assertIs<ParsedArgs.Tag>(parsed)
        assertEquals("smoke", tag.tag)
        assertEquals("http://127.0.0.1:47301", tag.baseUrl)
        assertTrue(tag.interactive)
    }

    @Test
    fun `parseArgs -i 短名等价 --interactive`() {
        val parsed = parseArgs(arrayOf("-i", "select_ratio", "http://127.0.0.1:47301"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertTrue(single.interactive, "-i 短名应识别为 interactive=true")
    }

    @Test
    fun `parseArgs --interactive + --tag 组合`() {
        val parsed = parseArgs(arrayOf("--interactive", "--tag", "smoke", "http://127.0.0.1:47301"))
        val tag = assertIs<ParsedArgs.Tag>(parsed)
        assertEquals("smoke", tag.tag)
        assertEquals("http://127.0.0.1:47301", tag.baseUrl)
        assertTrue(tag.interactive)
    }

    @Test
    fun `parseArgs 非 interactive 默认 false`() {
        val parsed = parseArgs(arrayOf("select_ratio", "http://127.0.0.1:47301"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertEquals(false, single.interactive)
        assertEquals(false, single.tailEvents)
    }

    // ---- parseArgs：--tail-events ----

    @Test
    fun `parseArgs --tail-events 单场景`() {
        val parsed = parseArgs(arrayOf("--tail-events", "select_ratio", "http://127.0.0.1:47301"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertEquals("select_ratio", single.scenario)
        assertTrue(single.tailEvents, "tail-events 应识别为 true")
        assertEquals(false, single.interactive, "只传 --tail-events 不该同时开 interactive")
    }

    @Test
    fun `parseArgs --tail-events 位置自由 - 尾部`() {
        val parsed = parseArgs(arrayOf("select_ratio", "http://127.0.0.1:47301", "--tail-events"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertTrue(single.tailEvents)
    }

    @Test
    fun `parseArgs --tail-events + --interactive + --tag 三者叠加`() {
        val parsed = parseArgs(
            arrayOf("--interactive", "--tail-events", "--tag", "smoke", "http://127.0.0.1:47301"),
        )
        val tag = assertIs<ParsedArgs.Tag>(parsed)
        assertEquals("smoke", tag.tag)
        assertTrue(tag.interactive)
        assertTrue(tag.tailEvents, "--interactive 与 --tail-events 应能同时启用")
    }

    @Test
    fun `parseArgs tag 形态不带 interactive`() {
        val parsed = parseArgs(arrayOf("--tag", "smoke", "http://127.0.0.1:47301"))
        val tag = assertIs<ParsedArgs.Tag>(parsed)
        assertEquals("smoke", tag.tag)
        assertEquals("http://127.0.0.1:47301", tag.baseUrl)
        assertEquals(false, tag.interactive)
    }

    @Test
    fun `parseArgs 参数缺失返回 null`() {
        // 只传一个 positional
        assertNull(parseArgs(arrayOf("select_ratio")))
        // 空 args
        assertNull(parseArgs(arrayOf()))
        // 只有 flag 没 positional
        assertNull(parseArgs(arrayOf("--interactive")))
    }

    @Test
    fun `parseArgs --tag 缺少 tag 或 baseUrl 返回 null`() {
        // --tag 后只有一个 positional
        assertNull(parseArgs(arrayOf("--tag", "smoke")))
        // --tag 单独
        assertNull(parseArgs(arrayOf("--tag")))
    }

    @Test
    fun `parseArgs positional 多余参数返回 null`() {
        // 单场景形态只允许 2 个 positional
        assertNull(parseArgs(arrayOf("select_ratio", "http://x", "extra")))
    }

    // ---- parseArgs：--all ----

    @Test
    fun `parseArgs --all 基本形态`() {
        val parsed = parseArgs(arrayOf("--all", "http://127.0.0.1:47301"))
        val all = assertIs<ParsedArgs.All>(parsed)
        assertEquals("http://127.0.0.1:47301", all.baseUrl)
        assertEquals(false, all.interactive)
        assertEquals(false, all.tailEvents)
    }

    @Test
    fun `parseArgs --all + --interactive + --tail-events 叠加`() {
        val parsed = parseArgs(
            arrayOf("--interactive", "--tail-events", "--all", "http://127.0.0.1:47301"),
        )
        val all = assertIs<ParsedArgs.All>(parsed)
        assertEquals("http://127.0.0.1:47301", all.baseUrl)
        assertEquals(true, all.interactive)
        assertEquals(true, all.tailEvents)
    }

    @Test
    fun `parseArgs --all 缺 baseUrl 返回 null`() {
        assertNull(parseArgs(arrayOf("--all")))
    }

    @Test
    fun `parseArgs --all 多余参数返回 null`() {
        assertNull(parseArgs(arrayOf("--all", "http://x", "extra")))
    }

    // ---- parseArgs：逗号分隔多场景（e2e.sh 分组跑批形态） ----

    @Test
    fun `parseArgs 逗号分隔两名解析为 Scenarios 且保序`() {
        val parsed = parseArgs(arrayOf("select_ratio,play_pause", "http://127.0.0.1:47301"))
        val list = assertIs<ParsedArgs.Scenarios>(parsed)
        assertEquals(listOf("select_ratio", "play_pause"), list.names)
        assertEquals("http://127.0.0.1:47301", list.baseUrl)
        assertEquals(false, list.interactive)
        assertEquals(false, list.tailEvents)
    }

    @Test
    fun `parseArgs 逗号分隔带空格与空段会规整`() {
        // e2e.sh 拼 group members 时不会产空段，这里锁防御语义：trim + 滤空
        val parsed = parseArgs(arrayOf("select_ratio, play_pause,,apply_pip,", "http://127.0.0.1:47301"))
        val list = assertIs<ParsedArgs.Scenarios>(parsed)
        assertEquals(listOf("select_ratio", "play_pause", "apply_pip"), list.names)
    }

    @Test
    fun `parseArgs 单名不含逗号保持 Single 语义`() {
        // 尾逗号规整后只剩一名 → 仍归 Single（与 e2e.sh 单场景组行为一致）
        val parsed = parseArgs(arrayOf("select_ratio,", "http://127.0.0.1:47301"))
        val single = assertIs<ParsedArgs.Single>(parsed)
        assertEquals("select_ratio", single.scenario)
    }

    @Test
    fun `parseArgs 逗号分隔 + flags 叠加`() {
        val parsed = parseArgs(
            arrayOf("--interactive", "--tail-events", "select_ratio,play_pause", "http://127.0.0.1:47301"),
        )
        val list = assertIs<ParsedArgs.Scenarios>(parsed)
        assertEquals(listOf("select_ratio", "play_pause"), list.names)
        assertEquals(true, list.interactive)
        assertEquals(true, list.tailEvents)
    }

    // ---- runOne：三态 outcome ----

    @Test
    fun `runOne PASS 场景返回 RunOutcome PASS`() {
        val meta = ScenarioMeta(
            name = "test_pass",
            tags = setOf("test"),
            page = HostPage.DEMO,
            run = { passResult("test_pass") },
        )
        val session = InteractiveSession.disabled()
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { failOnFailNotExpected(it) })
        assertEquals(RunOutcome.PASS, outcome)
    }

    @Test
    fun `runOne FAIL 场景 - scenario 返回 pass=false 归为 FAIL`() {
        val meta = ScenarioMeta(
            name = "test_fail_soft",
            tags = setOf("test"),
            page = HostPage.DEMO,
            run = { failResult("test_fail_soft", "soft assertion failed") },
        )
        val session = InteractiveSession.disabled()
        var onFailInvokedWith: String? = null
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { onFailInvokedWith = it })
        assertEquals(RunOutcome.FAIL, outcome)
        assertEquals(
            "http://127.0.0.1:1",
            onFailInvokedWith,
            "scenario 返回 pass=false 时应调 onFail 传 baseUrl —— 现场保留信息打印链路",
        )
    }

    @Test
    fun `runOne FAIL 场景 - 抛 AssertionError 归为 RunOutcome FAIL 并调 onFail`() {
        // AssertionError 是 handeyeTest 已收敛的路径；此 case 兜底：runner 层若真拿到未预期
        // 异常仍要转成 FAIL，避免因兼容问题让整条流水静默 exit 0。
        val meta = ScenarioMeta(
            name = "test_fail_assertion",
            tags = setOf("test"),
            page = HostPage.DEMO,
            run = { throw AssertionError("模拟场景断言失败") },
        )
        val session = InteractiveSession.disabled()
        var onFailInvokedWith: String? = null
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { onFailInvokedWith = it })
        assertEquals(RunOutcome.FAIL, outcome)
        assertEquals("http://127.0.0.1:1", onFailInvokedWith)
    }

    @Test
    fun `runOne FAIL 场景 - 抛任意 Throwable 也归为 RunOutcome FAIL 并调 onFail`() {
        var onFailCalls = 0
        val meta = ScenarioMeta(
            name = "test_fail_runtime",
            tags = setOf("test"),
            page = HostPage.DEMO,
            run = { throw RuntimeException("模拟未预期异常") },
        )
        val session = InteractiveSession.disabled()
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { onFailCalls++ })
        assertEquals(RunOutcome.FAIL, outcome)
        assertEquals(1, onFailCalls, "runtime 异常路径也应调 onFail")
    }

    @Test
    fun `runOne QUIT 场景 - 抛 E2EQuitException 返回 RunOutcome QUIT 且不调 onFail`() {
        var onFailCalls = 0
        val meta = ScenarioMeta(
            name = "test_quit",
            tags = setOf("test"),
            page = HostPage.DEMO,
            run = { throw E2EQuitException("场景内 breakpoint 用户键入 q") },
        )
        val session = InteractiveSession.disabled()
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { onFailCalls++ })
        assertEquals(RunOutcome.QUIT, outcome)
        assertEquals(0, onFailCalls, "QUIT 路径不应触发现场保留")
    }

    @Test
    fun `runOne PASS 场景 - 不调 onFail`() {
        var onFailCalls = 0
        val meta = ScenarioMeta(
            "test_pass_no_onfail",
            setOf("test"),
            page = HostPage.DEMO,
            run = { passResult("test_pass_no_onfail") },
        )
        val session = InteractiveSession.disabled()
        val outcome = runOne(meta, "http://127.0.0.1:1", session, onFail = { onFailCalls++ })
        assertEquals(RunOutcome.PASS, outcome)
        assertEquals(0, onFailCalls, "PASS 路径不应触发现场保留")
    }

    @Test
    fun `runOne 上报 ScenarioResult - PASS 场景`() {
        val expected = passResult("test_report_pass", elapsedMs = 42L)
        val meta = ScenarioMeta("test_report_pass", setOf("test"), HostPage.DEMO, run = { expected })
        var reported: ScenarioResult? = null
        runOne(
            meta,
            "http://127.0.0.1:1",
            InteractiveSession.disabled(),
            onFail = { /* no-op */ },
            onResult = { reported = it },
        )
        assertEquals(expected, reported, "PASS 场景 onResult 应透传 scenario 返回的 ScenarioResult")
    }

    @Test
    fun `runOne 上报 ScenarioResult - FAIL 场景（异常路径合成失败结果）`() {
        val meta = ScenarioMeta("test_report_ex", setOf("test"), HostPage.DEMO, run = { throw RuntimeException("boom") })
        var reported: ScenarioResult? = null
        runOne(
            meta,
            "http://127.0.0.1:1",
            InteractiveSession.disabled(),
            onFail = { /* no-op */ },
            onResult = { reported = it },
        )
        val r = requireNotNull(reported) { "异常路径也应通过 onResult 合成 ScenarioResult 上报" }
        assertEquals("test_report_ex", r.name)
        assertEquals(false, r.pass)
    }

    /**
     * 单测里没有真 debug server，走默认 printer 会命中 tryFetchHealth 的兜底路径（HTTP 抓失败
     * 时返 (null, null)）——本 helper 只在测试用「onFail 不该被调」的路径上安装，收到调用直接失败。
     */
    private fun failOnFailNotExpected(baseUrl: String): Unit =
        error("onFail 不应被 PASS 场景调用，收到 baseUrl=$baseUrl")

    // ---- runOne interactive：before/after prompt 交互 ----

    /**
     * Spy session—记录 pause(label) 的调用序列 + 支持在指定 label 前缀抛 E2EQuitException 模拟用户 q。
     * runScenario 委托给 meta.run，避免 ThreadLocal 副作用。
     *
     * `quitAtLabelPrefix` 用 startsWith 匹配，避免 scenario name 里有 "before" / "after" 子串导致误触发。
     */
    private class SpySession(
        input: BufferedReader = BufferedReader(StringReader("")),
        output: PrintStream = PrintStream(ByteArrayOutputStream()),
        val quitAtLabelPrefix: String? = null,
    ) : InteractiveSession(enabled = true, input = input, output = output) {
        val pauseLabels = mutableListOf<String>()
        override fun pause(label: String) {
            pauseLabels += label
            if (quitAtLabelPrefix != null && label.startsWith(quitAtLabelPrefix)) {
                throw E2EQuitException("Spy quit at $label")
            }
        }
        override fun runScenario(meta: ScenarioMeta, baseUrl: String): ScenarioResult {
            return meta.run(baseUrl)
        }
    }

    @Test
    fun `runOne interactive - PASS 场景问 before 和 after 两次 pause`() {
        val meta = ScenarioMeta("test_pass", setOf("test"), HostPage.DEMO, run = { passResult("test_pass") })
        val session = SpySession()
        val outcome = runOne(meta, "http://x", session, onFail = { /* no-op */ })
        assertEquals(RunOutcome.PASS, outcome)
        assertEquals(2, session.pauseLabels.size, "PASS 场景应问 before + after 两次 pause")
        assertTrue(session.pauseLabels[0].startsWith("before test_pass"), "首次 pause 应是 before：${session.pauseLabels[0]}")
        assertTrue(session.pauseLabels[1].startsWith("after test_pass"), "末次 pause 应是 after：${session.pauseLabels[1]}")
        assertTrue(session.pauseLabels[1].contains("PASS"), "after prompt 应回显 outcome：${session.pauseLabels[1]}")
    }

    @Test
    fun `runOne interactive - FAIL 场景只问 before 一次 pause 不问 after`() {
        val meta = ScenarioMeta("test_fail", setOf("test"), HostPage.DEMO, run = { throw AssertionError("failed") })
        val session = SpySession()
        // 传 no-op onFail：本 case 只关心 pause 序列，不测现场保留输出（那由独立 case 覆盖），
        // 顺带避免默认 printer 对 http://x 发无意义请求。
        val outcome = runOne(meta, "http://x", session, onFail = { /* no-op */ })
        assertEquals(RunOutcome.FAIL, outcome)
        // FAIL 后跳过 after prompt，避免用户键入 q 掩盖 FAIL——语义详见 runOne KDoc
        assertEquals(1, session.pauseLabels.size, "FAIL 场景不应问 after prompt，实际 pause 序列：${session.pauseLabels}")
        assertTrue(session.pauseLabels[0].startsWith("before test_fail"))
    }

    @Test
    fun `runOne interactive - before prompt 键入 q 直接返回 QUIT 不跑场景`() {
        var scenarioRan = false
        val meta = ScenarioMeta(
            "test_scenario",
            setOf("test"),
            page = HostPage.DEMO,
            run = { scenarioRan = true; passResult("test_scenario") },
        )
        val session = SpySession(quitAtLabelPrefix = "before")
        val outcome = runOne(meta, "http://x", session, onFail = { /* no-op */ })
        assertEquals(RunOutcome.QUIT, outcome)
        assertEquals(1, session.pauseLabels.size, "before quit 后不该再 pause")
        assertEquals(false, scenarioRan, "before quit 后不该执行 scenario body")
    }

    @Test
    fun `runOne interactive - PASS 后 after prompt 键入 q 返回 QUIT`() {
        val meta = ScenarioMeta("test_scenario", setOf("test"), HostPage.DEMO, run = { passResult("test_scenario") })
        val session = SpySession(quitAtLabelPrefix = "after")
        val outcome = runOne(meta, "http://x", session, onFail = { /* no-op */ })
        assertEquals(RunOutcome.QUIT, outcome)
        assertEquals(2, session.pauseLabels.size, "before + after 各一次 pause")
    }

    @Test
    fun `runOne non-interactive - session disabled 不调 pause`() {
        val meta = ScenarioMeta("test_no_pause", setOf("test"), HostPage.DEMO, run = { passResult("test_no_pause") })
        // 用 spy 但 enabled=false 走不到 open pause override——直接用 disabled() 验证
        val session = InteractiveSession.disabled()
        val outcome = runOne(meta, "http://x", session, onFail = { /* no-op */ })
        assertEquals(RunOutcome.PASS, outcome)
        // 无法直接断言 pause 未被调，但 disabled() 的 pause 内部 if (!enabled) return —— 走完不抛就 OK
    }

    // ---- defaultFailContextPrinter：通用现场保留输出 ----

    @Test
    fun `defaultFailContextPrinter prints target without host endpoint access`() {
        val stdout = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(stdout))
        try {
            defaultFailContextPrinter("custom-target")
        } finally {
            System.setOut(original)
        }
        val out = stdout.toString(Charsets.UTF_8)
        assertTrue(out.contains("[FAIL 现场保留]"), out)
        assertTrue(out.contains("target=custom-target"), out)
        assertTrue(out.contains("scenario context"), out)
    }

    // ---- computeExitCode：E2 新增汇总退出码 ----

    @Test
    fun `computeExitCode 全部 pass 返回 0`() {
        val results = listOf(
            passResult("a", elapsedMs = 10L),
            passResult("b", elapsedMs = 20L),
        )
        assertEquals(0, computeExitCode(results))
    }

    @Test
    fun `computeExitCode 任一 fail 返回 1`() {
        val results = listOf(
            passResult("a"),
            failResult("b", "step failed"),
        )
        assertEquals(1, computeExitCode(results))
    }

    @Test
    fun `computeExitCode 全部 fail 返回 1`() {
        val results = listOf(
            failResult("a", "reason1"),
            failResult("b", "reason2"),
        )
        assertEquals(1, computeExitCode(results))
    }

    @Test
    fun `computeExitCode 空列表返回 0`() {
        // e.g. --interactive 用户在 before prompt 直接键入 q，一条 ScenarioResult 都没产出
        assertEquals(0, computeExitCode(emptyList()))
    }

    // ---- helpers ----

    private fun passResult(name: String, elapsedMs: Long = 0L): ScenarioResult =
        ScenarioResult(
            name = name,
            pass = true,
            reporter = SoftAssertionReporter(),
            elapsedMs = elapsedMs,
        )

    private fun failResult(name: String, reason: String, elapsedMs: Long = 0L): ScenarioResult =
        ScenarioResult(
            name = name,
            pass = false,
            reporter = SoftAssertionReporter().apply { step(label = "case", pass = false, failReason = reason) },
            elapsedMs = elapsedMs,
        )
}
