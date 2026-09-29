package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.DiagnosticArtifact
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 失败 artifact dump（Stage 1 组件 3.3）。
 *
 * 只在 `reporter.hasFailure() == true` 时触发；PASS 场景零 IO。
 *
 * 落盘目录：`insedit/e2e/artifacts/<yyyy-MM-dd-HHmmss>/<scenarioName>/`
 * 落盘文件：host context 提供的 raw artifacts，以及 projected-facts.json / report.md。
 *
 * Raw artifacts parallel dump（任一失败不阻塞其它）；projected-facts / report.md 依 store 已有产物。
 */
object ArtifactDumper {

    private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    /** Resolves the repository artifact directory from any directory inside the repository. */
    internal fun defaultArtifactsRoot(cwd: File): File {
        val repositoryRoot = generateSequence(cwd.absoluteFile) { it.parentFile }
            .firstOrNull { root ->
                File(root, "settings.gradle.kts").isFile && File(root, "e2e").isDirectory
            }
            ?: error("Unable to locate insedit repository root from ${cwd.absolutePath}; set e2e.artifactsRoot")
        return File(repositoryRoot, "e2e/artifacts")
    }

    fun dumpIfFailed(
        scenarioName: String,
        reporter: SoftAssertionReporter,
        rawArtifacts: List<DiagnosticArtifact>,
        projectedFactsJson: String,
        environmentJson: String,
    ): String? {
        if (!reporter.hasFailure()) return null
        val ts = LocalDateTime.now().format(TS_FMT)
        // 通过系统属性 e2e.artifactsRoot 覆盖；默认向上定位 insedit 仓库根目录，
        // 因而 Gradle :e2e-scenarios:run 的 e2e-scenarios cwd 不会产生嵌套路径。
        val artifactsRoot = System.getProperty("e2e.artifactsRoot")
            ?.let(::File)
            ?: defaultArtifactsRoot(File(""))
        val dir = File(artifactsRoot, "$ts/$scenarioName")
        dir.mkdirs()

        val threads = rawArtifacts.map { artifact ->
            Thread {
                runCatching {
                    File(dir, artifact.fileName).writeText(artifact.collect())
                }.onFailure { throwable ->
                    File(dir, "${artifact.fileName}.error")
                        .writeText("${throwable.javaClass.simpleName}: ${throwable.message}")
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(10_000) }

        // Facts + report
        File(dir, "projected-facts.json").writeText(projectedFactsJson)
        File(dir, "report.md").writeText(buildString {
            appendLine("# Scenario Report · $scenarioName")
            appendLine()
            appendLine("**Environment**")
            appendLine("```json")
            appendLine(environmentJson)
            appendLine("```")
            appendLine()
            appendLine("**Assertion Tree**")
            appendLine("```")
            appendLine(reporter.formatTree())
            appendLine("```")
        })
        return dir.absolutePath
    }
}
