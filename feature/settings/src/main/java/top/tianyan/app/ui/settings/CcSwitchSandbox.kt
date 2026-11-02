package top.tianyan.app.ui.settings

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * 读取 cc-switch-server 的 Web Basic 凭据与 CSRF token（username, password, csrfToken）。
 *
 * 服务端整棵 `/api` 路由都要求 HTTP Basic 鉴权，写操作还要求 `x-csrf-token` 头；
 * 凭据与 token 均落在沙箱 `~/.cc-switch` 下，故由沙箱侧读取后回传给网络层。
 *
 * 安全约定（审计 P0-03）：**缺失时返回 null，不再静默回落到 `admin/admin123`，也不代写固定口令**。
 * 调用方必须把 null 展示成「中枢 Web 凭据未配置，请在中枢页重置访问密码」并中止操作，
 * 避免公开默认口令成为局域网 Web 面的登录入口。
 */
internal suspend fun readCcSwitchWebCredentials(
    linuxRuntime: LinuxRuntime,
): Triple<String, String, String>? = withContext(Dispatchers.IO) {
    try {
        val cmd = """
            USER_FILE="${'$'}{HOME:-/root}/.cc-switch/web_username"
            PASS_FILE="${'$'}{HOME:-/root}/.cc-switch/web_password"
            ENV_FILE="${'$'}{HOME:-/root}/.cc-switch/web_env"
            [ -s "${'$'}USER_FILE" ] || exit 3
            [ -s "${'$'}PASS_FILE" ] || exit 3
            chmod 600 "${'$'}USER_FILE" "${'$'}PASS_FILE" 2>/dev/null || true
            cat "${'$'}USER_FILE"
            echo "---TIANYAN_SPLIT---"
            cat "${'$'}PASS_FILE"
            echo "---TIANYAN_SPLIT---"
            grep -m1 '^WEB_CSRF_TOKEN=' "${'$'}ENV_FILE" 2>/dev/null | cut -d= -f2- || true
        """.trimIndent()
        val res = linuxRuntime.execute(ShellCommand(commandLine = cmd, timeoutMs = 5000L))
        if (!res.isSuccess) return@withContext null
        val parts = res.stdout.split("---TIANYAN_SPLIT---")
        val username = parts.getOrNull(0)?.trim().orEmpty()
        val password = parts.getOrNull(1)?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) return@withContext null
        Triple(username, password, parts.getOrNull(2)?.trim().orEmpty())
    } catch (_: Exception) {
        null
    }
}

/** 生成 24 位强随机 Web 访问密码（替代历史默认口令 admin123）。 */
internal fun generateCcSwitchPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = SecureRandom()
    return buildString(24) { repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) } }
}
