package top.tianyan.app.runtime.shell

/**
 * Host-side argv/env for launching an interactive PRoot shell under an external
 * PTY owner (Termux [com.termux.terminal.TerminalSession] JNI).
 *
 * [arguments] is the full argv (including argv[0] == [executable]).
 * [environment] entries are `KEY=VALUE` as expected by Termux JNI.
 */
data class InteractiveLaunchSpec(
    val executable: String,
    val arguments: Array<String>,
    val workingDirectory: String,
    val environment: Array<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InteractiveLaunchSpec) return false
        return executable == other.executable &&
            arguments.contentEquals(other.arguments) &&
            workingDirectory == other.workingDirectory &&
            environment.contentEquals(other.environment)
    }

    override fun hashCode(): Int {
        var result = executable.hashCode()
        result = 31 * result + arguments.contentHashCode()
        result = 31 * result + workingDirectory.hashCode()
        result = 31 * result + environment.contentHashCode()
        return result
    }
}
