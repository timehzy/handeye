package dev.handeye.orchestrator.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Task 4.8：Snapshot diff 命令行入口。
 *
 * ## 用法
 *
 * ```
 * ScenarioRunner snapshot diff <a.json> <b.json>
 * ```
 *
 * 由 [main][dev.handeye.orchestrator.orchestrator.main] 在 `args[0] == "snapshot"` 时分派——
 * 复用 application 插件的 mainClass，不新增 gradle task。用户从命令行走：
 *
 * ```
 * ./gradlew :e2e-scenarios:run --args="snapshot diff a.json b.json"
 * ./e2e.sh snapshot diff a.json b.json
 * ```
 *
 * ## 输出格式
 *
 * 只打有变更的字段，未变的不打（noise 太多）。每行前缀跟 git diff 类似：
 *
 * ```
 * ~ editModule.ratio: "9:16" -> "16:9"
 * + editModule.filter.beauty: true
 * - renderParams: "old"
 * ```
 *
 * 末行汇总：`==> 差异共 N 项 (ADDED=x REMOVED=y CHANGED=z)`；无差异时打 `==> 无差异`。
 *
 * ## 退出码
 *
 * - 0  成功（有无差异都算成功——本命令是查看工具，不是 assert）
 * - 2  参数错误 / 文件不存在 / 非 JSON / 非 JsonObject
 *
 * 保留 exit 0 而非「有差异就 exit 1」——用户在 shell 里把结果 pipe 给 grep / wc 才是常规
 * 用法，「diff 非空 = fail」会让 CI 或 pipeline 判定复杂。要严格 assert 走单测 / scenario。
 */
object SnapshotDiffCli {

    /**
     * @param stdout 打印目的地——默认 [System.out]，单测可注入 [java.io.ByteArrayOutputStream]
     * @param stderr 错误目的地——默认 [System.err]
     * @return 进程退出码（由调用方决定是否真的 [exitProcess]，方便单测断言不 kill JVM）
     */
    fun run(
        args: List<String>,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
    ): Int {
        if (args.size != 3 || args[0] != "diff") {
            stderr.println("用法: snapshot diff <a.json> <b.json>")
            return 2
        }
        val aPath = args[1]
        val bPath = args[2]
        val aJson = readJsonObjectOrNull(aPath, stderr) ?: return 2
        val bJson = readJsonObjectOrNull(bPath, stderr) ?: return 2

        val entries = SnapshotDiff.diff(aJson, bJson)
        if (entries.isEmpty()) {
            stdout.println("==> 无差异")
            return 0
        }
        var added = 0
        var removed = 0
        var changed = 0
        for (e in entries) {
            when (e.kind) {
                SnapshotDiff.DiffKind.ADDED -> {
                    added++
                    stdout.println("+ ${e.path}: ${e.rightValue}")
                }
                SnapshotDiff.DiffKind.REMOVED -> {
                    removed++
                    stdout.println("- ${e.path}: ${e.leftValue}")
                }
                SnapshotDiff.DiffKind.CHANGED -> {
                    changed++
                    stdout.println("~ ${e.path}: ${e.leftValue} -> ${e.rightValue}")
                }
            }
        }
        stdout.println("==> 差异共 ${entries.size} 项 (ADDED=$added REMOVED=$removed CHANGED=$changed)")
        return 0
    }

    private fun readJsonObjectOrNull(pathStr: String, stderr: PrintStream): JsonObject? {
        val path = Path.of(pathStr)
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            stderr.println("错误: 文件不存在: $pathStr")
            return null
        } catch (t: Throwable) {
            stderr.println("错误: 读取 $pathStr 失败: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        val el = try {
            Json.parseToJsonElement(text)
        } catch (t: Throwable) {
            stderr.println("错误: $pathStr 不是合法 JSON: ${t.message}")
            return null
        }
        return try {
            el.jsonObject
        } catch (_: IllegalArgumentException) {
            stderr.println("错误: $pathStr 顶层不是 JSON object（snapshot 文件应是对象）")
            null
        }
    }
}
