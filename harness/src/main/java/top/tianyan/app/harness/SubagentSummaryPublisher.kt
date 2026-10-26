package top.tianyan.app.harness

import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.environment.SessionExecutionEnvironments
import top.tianyan.app.runtime.LinuxEnvironmentManager

/** Save reports through the parent world's files/artifacts, after the same redaction as tool output. */
class SubagentSummaryPublisher(
    private val localFiles: WorkspaceFileAccess,
    private val environments: SessionExecutionEnvironments? = null,
    private val redactor: SecretRedactor = SecretRedactor(),
    private val linuxEnvironment: LinuxEnvironmentManager? = null,
    private val preferences: AgentPreferences? = null,
) {
    internal suspend fun publish(outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>,
        sessionId: String, workspace: String): String {
        val environment = environments?.environment(sessionId, workspace)
        val files = environment?.files ?: if (workspace.isBlank()) localFiles else localFiles.withBase(workspace)
        val sanitized = outcomes.map { it.copy(summary = top.tianyan.app.harness.environment.redactExecutionOutput(
            it.summary, redactor, environments, linuxEnvironment, preferences, sessionId, workspace)) }
        return paginateSubagentSummary(sanitized, environment?.id?.workspace ?: workspace, files, environment?.artifacts)
    }
}
