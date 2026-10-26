package top.tianyan.app.runtime.shell

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    /** Executor diagnostic appended to stderr; not part of the child's captured stream. */
    val outputDiagnostic: String? = null,
    /** Time waiting for local resource admission, separate from child execution and its timeout. */
    val resourceWaitMs: Long = 0,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
