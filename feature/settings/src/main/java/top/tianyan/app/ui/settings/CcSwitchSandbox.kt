package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * 读取 cc-switch-server 的 Web Basic 凭据与 CSRF token（username, password, csrfToken）。
 *
 * 服务端整棵 `/api` 路由都要求 HTTP Basic 鉴权，写操作还要求 `x-csrf-token` 头；
 * 凭据与 token 均落在沙箱 `~/.cc-switch` 下，故由沙箱侧读取后回传给网络层。
 */
internal suspend fun readCcSwitchWebCredentials(
    linuxRuntime: LinuxRuntime,
): Triple<String, String, String> = withContext(Dispatchers.IO) {
    try {
        val cmd = """
            USER_FILE="${'$'}{HOME:-/root}/.cc-switch/web_username"
            PASS_FILE="${'$'}{HOME:-/root}/.cc-switch/web_password"
            ENV_FILE="${'$'}{HOME:-/root}/.cc-switch/web_env"
            if [ ! -s "${'$'}USER_FILE" ]; then
                mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                printf 'admin' > "${'$'}USER_FILE" 2>/dev/null || true
            fi
            if [ ! -s "${'$'}PASS_FILE" ]; then
                mkdir -p "${'$'}{HOME:-/root}/.cc-switch" 2>/dev/null || true
                printf 'admin123' > "${'$'}PASS_FILE" 2>/dev/null || true
            fi
            chmod 600 "${'$'}USER_FILE" "${'$'}PASS_FILE" 2>/dev/null || true
            cat "${'$'}USER_FILE" 2>/dev/null || echo "admin"
            echo "---TIANYAN_SPLIT---"
            cat "${'$'}PASS_FILE" 2>/dev/null || echo "admin123"
            echo "---TIANYAN_SPLIT---"
            grep -m1 '^WEB_CSRF_TOKEN=' "${'$'}ENV_FILE" 2>/dev/null | cut -d= -f2- || echo ""
        """.trimIndent()
        val res = linuxRuntime.execute(ShellCommand(commandLine = cmd, timeoutMs = 5000L))
        if (!res.isSuccess) return@withContext Triple("admin", "admin123", "")
        val parts = res.stdout.split("---TIANYAN_SPLIT---")
        val username = parts.getOrNull(0)?.trim().orEmpty().ifBlank { "admin" }
        val password = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "admin123" }
        val csrfToken = parts.getOrNull(2)?.trim().orEmpty()
        Triple(username, password, csrfToken)
    } catch (_: Exception) {
        Triple("admin", "admin123", "")
    }
}
