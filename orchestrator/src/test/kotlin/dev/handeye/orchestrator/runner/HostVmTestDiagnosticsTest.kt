package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.DiagnosticArtifact
import dev.handeye.orchestrator.context.DiagnosticContext
import dev.handeye.orchestrator.context.E2eContext
import dev.handeye.orchestrator.dispatcher.DispatchResult
import dev.handeye.orchestrator.dispatcher.Dispatcher
import dev.handeye.orchestrator.events.E2eEvent
import dev.handeye.orchestrator.events.EventsFetcher
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HostVmTestDiagnosticsTest {

    @Test
    fun `generic handeyeTest writes context-provided raw failure artifacts`() {
        val artifactsRoot = Files.createTempDirectory("host-vm-diagnostics").toFile()
        val originalRoot = System.getProperty("e2e.artifactsRoot")
        try {
            System.setProperty("e2e.artifactsRoot", artifactsRoot.absolutePath)
            val ctx = E2eContext(
                dispatcher = object : Dispatcher {
                    override suspend fun dispatch(action: String, payload: JsonObject) =
                        DispatchResult(true, null, null, null)
                },
                factSources = emptyMap(),
                eventsFetcher = object : EventsFetcher {
                    override suspend fun fetchAfter(seq: Long, kinds: Set<String>?, limit: Int) =
                        emptyList<E2eEvent>()
                },
                diagnostics = DiagnosticContext(
                    target = "context-target",
                    rawArtifacts = listOf(
                        DiagnosticArtifact("raw-context.txt") { "context-provided" },
                    ),
                ),
            )

            val result = handeyeTest(
                name = "context_diagnostics",
                ctx = ctx,
                block = {
                    act { error("expected failure") }
                },
            )

            assertFalse(result.pass)
            val rawArtifact = artifactsRoot.walkTopDown()
                .first { it.isFile && it.name == "raw-context.txt" }
            assertEquals("context-provided", rawArtifact.readText())
        } finally {
            if (originalRoot == null) {
                System.clearProperty("e2e.artifactsRoot")
            } else {
                System.setProperty("e2e.artifactsRoot", originalRoot)
            }
            artifactsRoot.deleteRecursively()
        }
    }
}
