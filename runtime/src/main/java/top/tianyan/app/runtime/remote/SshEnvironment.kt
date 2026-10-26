package top.tianyan.app.runtime.remote

internal const val SSH_ENV_PROBE_KEY = "__TIANYAN_SSH_ENV_PROBE"
internal const val SSH_ENV_PROBE_VALUE = "1"
/** Fixed argv. A login shell or `/etc/profile` would mix host output into the probe. */
internal const val SSH_ENV_PROBE_COMMAND = "printf '%s' \"\$$SSH_ENV_PROBE_KEY\""

private val SSH_ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Phone bridge credentials are not a remote SSH user's secret. The whole prefix stays on device. */
internal fun isBridgeCredentialKey(key: String): Boolean =
    key.trim().startsWith("TIANYAN_BRIDGE_", ignoreCase = true)

/**
 * Settings variables, then the command's own environment. A command key replaces the user's value.
 * Bridge credentials and the setEnv probe name are removed from both sides.
 */
internal fun forwardedSshEnvironment(user: Map<String, String>, command: Map<String, String>): Map<String, String> {
    fun forwardable(key: String) = !isBridgeCredentialKey(key) && key != SSH_ENV_PROBE_KEY && SSH_ENVIRONMENT_KEY.matches(key)
    command.forEach { (key, _) ->
        if (!isBridgeCredentialKey(key) && key != SSH_ENV_PROBE_KEY) {
            require(SSH_ENVIRONMENT_KEY.matches(key)) { "非法环境变量名称" }
        }
    }
    val merged = LinkedHashMap<String, String>()
    user.forEach { (key, value) -> if (forwardable(key)) merged[key] = value }
    command.forEach { (key, value) -> if (forwardable(key)) merged[key] = value }
    merged.values.forEach { require('\u0000' !in it) { "命令参数包含非法字符" } }
    return merged
}
