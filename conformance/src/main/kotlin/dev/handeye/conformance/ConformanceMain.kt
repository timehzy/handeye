package dev.handeye.conformance

import java.io.File
import kotlin.system.exitProcess

/**
 * conformance CLI：
 *   validate <file.jsonl>                    —— 结构校验，违规打印到 stdout，exit 0/1
 *   diff <actual.jsonl> <golden.jsonl>       —— golden 对拍（忽略 t 与 seq），exit 0/1
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (args[0]) {
        "validate" -> {
            if (args.size != 2) usage()
            val violations = SchemaValidator.validate(File(args[1]).readText())
            if (violations.isEmpty()) {
                println("OK: ${args[1]}")
            } else {
                violations.forEach { println("${it.line}: ${it.message}") }
                exitProcess(1)
            }
        }
        "diff" -> {
            if (args.size != 3) usage()
            val diffs = GoldenDiffer.diff(File(args[1]).readText(), File(args[2]).readText())
            if (diffs.isEmpty()) {
                println("OK: ${args[1]} == ${args[2]}")
            } else {
                diffs.forEach { println(it) }
                exitProcess(1)
            }
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("用法:")
    System.err.println("  conformance validate <file.jsonl>")
    System.err.println("  conformance diff <actual.jsonl> <golden.jsonl>")
    exitProcess(2)
}
