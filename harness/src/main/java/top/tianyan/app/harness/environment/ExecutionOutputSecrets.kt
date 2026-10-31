package top.tianyan.app.harness.environment

import top.tianyan.app.runtime.LinuxEnvironmentManager
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.security.SecretRedactor

/** SSH authentication material must be masked even when environment privacy is disabled. */
internal suspend fun redactExecutionOutput(value: String, redactor: SecretRedactor,
    environments: SessionExecutionEnvironments?, legacyVariables: LinuxEnvironmentManager?, preferences: AgentPreferences?,
    sessionId: String, workspace: String): String =
    executionOutputRedactor(redactor, environments, legacyVariables, preferences, sessionId, workspace)(value)

/** Capture this world's privacy policy once, before a bounded search starts cropping matching lines. */
internal suspend fun executionOutputRedactor(redactor: SecretRedactor,
    environments: SessionExecutionEnvironments?, legacyVariables: LinuxEnvironmentManager?, preferences: AgentPreferences?,
    sessionId: String, workspace: String): (String) -> String {
    val secrets = executionOutputSecrets(environments, legacyVariables, sessionId, workspace)
    val ssh = environments?.environment(sessionId, workspace)?.id?.backend == "ssh"
    val privacy = ssh || (preferences?.environmentPrivacyMode?.first() ?: true)
    val protectedSecrets = if (ssh) secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length } else emptyList()
    return { value ->
        val protected = protectedSecrets.fold(value) { output, secret -> output.replace(secret, "[REDACTED]") }
        redactor.redact(protected, secrets, privacy)
    }
}

/** Bound worlds never consult the phone's active distribution to sanitize their output. */
internal suspend fun executionOutputSecrets(environments: SessionExecutionEnvironments?,
    legacyVariables: LinuxEnvironmentManager?, sessionId: String, workspace: String): Collection<String> {
    if (environments != null) return environments.environment(sessionId, workspace).outputSecrets()
    legacyVariables?.refreshIfNeeded()?.getOrThrow()
    return legacyVariables?.values?.value?.values.orEmpty()
}
