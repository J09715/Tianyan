package top.tianyan.app.runtime.shell

import java.io.File

interface ShellExecutor {
    /** Failed foreground teardown remains reachable and blocks destructive maintenance. */
    suspend fun closePending() {}
    fun hasPending(): Boolean = false
    /** Failed teardown, rather than an ordinarily running foreground child. */
    fun hasUnsettled(): Boolean = false
    suspend fun execute(
        command: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = ShellCommand.DEFAULT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): CommandResult
}

