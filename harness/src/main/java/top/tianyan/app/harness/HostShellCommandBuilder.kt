package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.common.shell.ShellQuote

/**
 * host 工具的 shell 命令构造器：把 action + 参数翻译成宿主侧 /system/bin 命令行。
 *
 * 独立于执行通道（PrivilegeManager / Shizuku / ADB），只做纯函数拼装与转义，
 * 便于对命令构造与引号转义规则单独单测。执行由 [HostCapabilityToolBackend] 负责。
 */
internal object HostShellCommandBuilder {

    fun build(action: String, args: JsonObject): String = when (action) {
        "exec" -> JsonArgs.requireString(args, "command")
        "settings_get" -> {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            "/system/bin/settings get $namespace ${ShellQuote.of(key)}"
        }
        "settings_put" -> {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            val value = JsonArgs.requireString(args, "value")
            // 屏幕亮度写入：自适应亮度开启时系统会忽略手动值，先切到手动模式；
            // 写入后回读验证，因为 Shizuku UserService 进程若 UID 非 shell，
            // WRITE_SETTINGS 会被 SettingsProvider 静默拒绝（exit 0 但值不变）。
            if (namespace == "system" && key == "screen_brightness") {
                val quoted = ShellQuote.of(value)
                buildString {
                    append("/system/bin/settings put system screen_brightness_mode 0; ")
                    append("/system/bin/settings put system screen_brightness $quoted; ")
                    append("echo \"uid=$(id -u) mode=$(/system/bin/settings get system screen_brightness_mode) ")
                    append("requested=$quoted actual=$(/system/bin/settings get system screen_brightness)\"")
                }
            } else {
                "/system/bin/settings put $namespace ${ShellQuote.of(key)} ${ShellQuote.of(value)}"
            }
        }
        "package_list" -> {
            val filter = args["filter"]?.jsonPrimitive?.content?.trim().orEmpty()
            "/system/bin/pm list packages" + if (filter.isBlank()) "" else " | /system/bin/grep -F -- ${ShellQuote.of(filter)}"
        }
        "package_disable", "package_enable", "package_uninstall_user", "app_freeze", "app_unfreeze", "app_grant_permission" -> {
            val packageName = requireHostIdentifier(args, "package", PACKAGE_NAME)
            val user = JsonArgs.optionalLong(args, "user", 0L, 0L, 999L)
            when (action) {
                "package_disable", "app_freeze" -> "/system/bin/pm disable-user --user $user ${ShellQuote.of(packageName)}"
                "package_enable", "app_unfreeze" -> "/system/bin/pm enable --user $user ${ShellQuote.of(packageName)}"
                "app_grant_permission" -> {
                    val permission = requireHostIdentifier(args, "permission", ANDROID_PERMISSION)
                    "/system/bin/pm grant ${ShellQuote.of(packageName)} ${ShellQuote.of(permission)}"
                }
                else -> "/system/bin/pm uninstall --user $user ${ShellQuote.of(packageName)}"
            }
        }
        "device_status" ->
            "echo '[battery]'; dumpsys battery | grep -E 'level|status|temperature'" +
                "; echo '[network]'; dumpsys connectivity | head -30" +
                "; echo '[foreground]'; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -6" +
                "; echo '[storage]'; df -h /data | tail -2"
        "logcat" -> {
            val lines = JsonArgs.optionalLong(args, "tail_lines", 200L, 1L, 2_000L)
            val tag = args["tag"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tag.isBlank()) "/system/bin/logcat -d -t $lines"
            else {
                require(LOGCAT_TAG.matches(tag)) { "logcat tag 格式不合法" }
                "/system/bin/logcat -d -t $lines -s ${ShellQuote.of("$tag:*")}"
            }
        }
        else -> throw IllegalArgumentException(
            "不支持的 host action：$action；可用 status/exec/settings_get/settings_put/package_list/package_disable/package_enable/package_uninstall_user/app_list/app_freeze/app_unfreeze/app_grant_permission/logcat",
        )
    }

    private fun requireSettingsNamespace(args: JsonObject): String {
        val namespace = JsonArgs.requireString(args, "namespace").trim().lowercase()
        require(namespace in setOf("system", "secure", "global")) { "namespace 仅支持 system/secure/global" }
        return namespace
    }

    private fun requireHostIdentifier(args: JsonObject, key: String, pattern: Regex): String {
        val value = JsonArgs.requireString(args, key).trim()
        require(pattern.matches(value)) { "$key 格式不合法" }
        return value
    }

    private val SETTINGS_KEY = Regex("^[A-Za-z0-9._-]{1,160}$")
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val ANDROID_PERMISSION = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val LOGCAT_TAG = Regex("^[A-Za-z0-9_.-]{1,80}$")
}
