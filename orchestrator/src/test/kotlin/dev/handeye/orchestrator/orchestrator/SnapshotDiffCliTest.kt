package dev.handeye.orchestrator.orchestrator

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 4.8：[SnapshotDiffCli.run] 打印格式 + 错误路径单测。
 *
 * 覆盖：
 * - 用法错误 → 退 2 + stderr 有 usage
 * - 文件不存在 → 退 2 + stderr 报文件不存在
 * - 非法 JSON → 退 2 + stderr 报「不是合法 JSON」
 * - 非 JSON object（数组）→ 退 2 + stderr 报「不是 JSON object」
 * - 相同 snapshot → 退 0 + stdout 打「无差异」
 * - 有 CHANGED / ADDED / REMOVED 混合 → 退 0 + stdout 有对应前缀行 + 汇总行
 */
class SnapshotDiffCliTest {

    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("snapshot-diff-cli-")
    }

    @AfterTest
    fun tearDown() {
        // best-effort：删掉临时目录
        Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun writeSnapshot(name: String, json: String): String {
        val p = tmpDir.resolve(name)
        p.writeText(json)
        return p.toString()
    }

    private fun runCli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = SnapshotDiffCli.run(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `wrong usage returns exit 2 with usage line on stderr`() {
        val (code, _, err) = runCli("diff")  // 只传子命令，缺文件
        assertEquals(2, code)
        assertTrue(err.contains("用法"), "缺少用法提示：$err")
    }

    @Test
    fun `unknown subcommand returns exit 2`() {
        val (code, _, err) = runCli("mutate", "a.json", "b.json")
        assertEquals(2, code)
        assertTrue(err.contains("用法"), err)
    }

    @Test
    fun `missing file returns exit 2 with clear error`() {
        val (code, _, err) = runCli("diff", "/no/such/a.json", "/no/such/b.json")
        assertEquals(2, code)
        assertTrue(err.contains("文件不存在"), "缺少文件不存在提示：$err")
    }

    @Test
    fun `invalid JSON returns exit 2 with clear error`() {
        val a = writeSnapshot("a.json", "{ not valid json")
        val b = writeSnapshot("b.json", "{}")
        val (code, _, err) = runCli("diff", a, b)
        assertEquals(2, code)
        assertTrue(err.contains("不是合法 JSON"), "缺少 JSON 解析错误提示：$err")
    }

    @Test
    fun `top-level array is rejected as not-object`() {
        val a = writeSnapshot("a.json", "[]")
        val b = writeSnapshot("b.json", "{}")
        val (code, _, err) = runCli("diff", a, b)
        assertEquals(2, code)
        assertTrue(err.contains("不是 JSON object"), err)
    }

    @Test
    fun `identical snapshots print no-diff banner and exit 0`() {
        val json = """{"t":100,"seq":1,"editModule":{"ratio":"16:9"}}"""
        val a = writeSnapshot("a.json", json)
        val b = writeSnapshot("b.json", json)
        val (code, out, _) = runCli("diff", a, b)
        assertEquals(0, code)
        assertTrue(out.contains("无差异"), "缺少「无差异」banner：$out")
    }

    @Test
    fun `mixed changes render each kind with prefix + summary line`() {
        // 顶层 CHANGED（t）+ 嵌套 CHANGED（editModule.ratio）+ 嵌套 ADDED（editModule.pip）
        // + 嵌套 REMOVED（editModule.beauty）
        val a = writeSnapshot(
            "a.json",
            """{"t":100,"seq":1,"editModule":{"ratio":"9:16","beauty":true}}""",
        )
        val b = writeSnapshot(
            "b.json",
            """{"t":200,"seq":1,"editModule":{"ratio":"16:9","pip":true}}""",
        )
        val (code, out, _) = runCli("diff", a, b)
        assertEquals(0, code)
        // CHANGED 用 `~ ` 前缀
        assertTrue(out.contains("~ t: 100 -> 200"), "缺少顶层 t CHANGED 行：$out")
        assertTrue(
            out.contains("~ editModule.ratio: \"9:16\" -> \"16:9\""),
            "缺少嵌套 ratio CHANGED 行：$out",
        )
        // ADDED 用 `+ ` 前缀
        assertTrue(out.contains("+ editModule.pip: true"), "缺少 ADDED 行：$out")
        // REMOVED 用 `- ` 前缀
        assertTrue(out.contains("- editModule.beauty: true"), "缺少 REMOVED 行：$out")
        // 汇总行
        assertTrue(
            out.contains("差异共 4 项") && out.contains("ADDED=1") &&
                out.contains("REMOVED=1") && out.contains("CHANGED=2"),
            "缺少或错误的汇总行：$out",
        )
    }

    @Test
    fun `unchanged fields are not printed`() {
        // "keep" 未变 → 不应出现在 stdout
        val a = writeSnapshot("a.json", """{"keep":"same","t":1}""")
        val b = writeSnapshot("b.json", """{"keep":"same","t":2}""")
        val (code, out, _) = runCli("diff", a, b)
        assertEquals(0, code)
        assertNotEquals(true, out.contains("keep"), "未变字段不应打印：$out")
        assertTrue(out.contains("~ t:"), "变化字段应打印：$out")
    }
}
