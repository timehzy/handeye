package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.DiagnosticArtifact
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArtifactDumperTest {
    @Test
    fun `dumpIfFailed returns null when reporter has no failure`() {
        val reporter = SoftAssertionReporter().apply { step("noop", pass = true, failReason = "") }
        val path = ArtifactDumper.dumpIfFailed(
            scenarioName = "test",
            reporter = reporter,
            rawArtifacts = listOf(DiagnosticArtifact("unused.json") { error("must not run") }),
            projectedFactsJson = "{}",
            environmentJson = "{}",
        )
        assertNull(path)
    }

    @Test
    fun `failure dump resolves e2e artifacts from e2e scenarios working directory`() {
        val workspace = Files.createTempDirectory("artifact-dumper").toFile()
        try {
            val repositoryRoot = File(workspace, "repo").apply { mkdirs() }
            File(repositoryRoot, "settings.gradle.kts").writeText("")
            File(repositoryRoot, "e2e").mkdirs()
            val scenariosWorkingDirectory = File(repositoryRoot, "e2e-scenarios").apply { mkdirs() }

            assertEquals(
                File(repositoryRoot, "e2e/artifacts").canonicalPath,
                ArtifactDumper.defaultArtifactsRoot(scenariosWorkingDirectory).canonicalPath,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `failure dump writes artifacts to configured root`() {
        val artifactsRoot = Files.createTempDirectory("artifact-dumper-output").toFile()
        val originalRoot = System.getProperty("e2e.artifactsRoot")
        try {
            System.setProperty("e2e.artifactsRoot", artifactsRoot.absolutePath)
            val reporter = SoftAssertionReporter().apply { step("failed", pass = false, failReason = "expected") }

            val path = assertNotNull(
                ArtifactDumper.dumpIfFailed(
                    scenarioName = "failure_case",
                    reporter = reporter,
                    rawArtifacts = listOf(
                        DiagnosticArtifact("custom-source.json") { "{\"raw\":true}" },
                    ),
                    projectedFactsJson = "{\"fact\":true}",
                    environmentJson = "{}",
                ),
            )

            val dumpDir = File(path)
            assertEquals(artifactsRoot.canonicalPath, dumpDir.parentFile?.parentFile?.canonicalPath)
            assertEquals("{\"raw\":true}", File(dumpDir, "custom-source.json").readText())
            assertEquals("{\"fact\":true}", File(dumpDir, "projected-facts.json").readText())
            assertEquals(true, File(dumpDir, "report.md").isFile)
        } finally {
            if (originalRoot == null) System.clearProperty("e2e.artifactsRoot") else System.setProperty("e2e.artifactsRoot", originalRoot)
            artifactsRoot.deleteRecursively()
        }
    }
}
