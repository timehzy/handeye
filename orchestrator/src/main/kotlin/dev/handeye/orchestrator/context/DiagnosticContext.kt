package dev.handeye.orchestrator.context

/**
 * One raw diagnostic artifact collected after a failed scenario.
 *
 * The host module owns the artifact name and collection strategy. The framework only persists the
 * returned content.
 */
data class DiagnosticArtifact(
    val fileName: String,
    val collect: () -> String,
)

/**
 * Optional transport metadata and raw diagnostics for one e2e target.
 *
 * @param target opaque target passed to the registered scenario runner
 * @param rawArtifacts host-provided raw diagnostic collectors
 * @param environment additional environment fields persisted with failure artifacts
 */
data class DiagnosticContext(
    val target: String,
    val rawArtifacts: List<DiagnosticArtifact> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)
