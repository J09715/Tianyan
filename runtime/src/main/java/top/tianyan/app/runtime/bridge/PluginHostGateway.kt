package top.tianyan.app.runtime.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Native-supplied identity; never inferred from a package's HTTP claims. */
@Serializable
data class PluginHostScope(
    val pluginId: String, val version: String, val generation: Long,
    val origin: String, val environment: String,
)

@Serializable
internal data class PluginHostRequest(val scope: PluginHostScope, val action: String, val target: String, val text: String? = null)

/**
 * 插件宿主动作网关 —— [HostCredentialAuthority] 之上的薄适配层。
 *
 * 只有受信任的原生代码可以在用户确认后签发票据；票据一次性、最多存活十秒，
 * 且永不进入 Web 内容或磁盘。HTTP 调用方既不能用旧共享密钥铸造、续期或放宽票据。
 * 本应用层边界不隔离同 uid 的 PRoot 进程。
 */
class PluginHostGateway(
    private val appInfo: () -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clipboardWrite: (String) -> Unit = { error("Clipboard unavailable") },
    private val installApkFile: (suspend (String) -> String)? = null,
    private val captureLogcat: (suspend () -> String)? = null,
    private val authority: HostCredentialAuthority = HostCredentialAuthority(now),
) {
    internal data class Response(val status: Int, val body: String)
    private val json = Json

    /** Called inside the approved native executor, using the document identity supplied by its host. */
    fun readAppInfo(scope: PluginHostScope, available: () -> Boolean): String {
        return invoke(PluginHostRequest(scope, "host.app-info", "application"), available)
    }

    /** Exact text is frozen into the one-shot ticket after the native user confirmation. */
    fun writeClipboard(scope: PluginHostScope, text: String, available: () -> Boolean): String =
        invoke(PluginHostRequest(scope, "host.clipboard-write", "clipboard", text), available)

    /** Installs the page-named APK through the privileged channel, under the same one-shot authority. */
    suspend fun installApk(scope: PluginHostScope, path: String, available: () -> Boolean): String =
        invokePrivileged(PluginHostRequest(scope, "host.install-apk", "apk", path), available)

    /** Bounded default log snapshot: the page names no tag, priority or line count. */
    suspend fun readLogcat(scope: PluginHostScope, available: () -> Boolean): String =
        invokePrivileged(PluginHostRequest(scope, "host.logcat", "logcat"), available)

    private fun invoke(request: PluginHostRequest, available: () -> Boolean): String {
        val token = issue(request, available)
        try {
            val response = handle(token, json.encodeToString(request))
            check(response.status == 200) { "Plugin host call denied" }
            return response.body
        } finally { revoke(token) }
    }

    private suspend fun invokePrivileged(request: PluginHostRequest, available: () -> Boolean): String {
        val token = issue(request, available)
        try {
            val response = handlePrivileged(token, json.encodeToString(request))
            check(response.status == 200) { "Plugin host call denied" }
            return response.body
        } finally { revoke(token) }
    }

    internal fun issue(request: PluginHostRequest, available: () -> Boolean): String {
        require(supported(request))
        val scope = request.scope
        require(listOf(scope.pluginId, scope.version, scope.origin, scope.environment).all { it.length in 1..256 })
        require(scope.generation >= 0 && scope.origin.startsWith("https://") && scope.environment == "android:local")
        return authority.issue(
            binding = HostCredentialAuthority.Binding(
                subject = scope.pluginId,
                version = scope.version,
                generation = scope.generation,
                origin = scope.origin,
                environment = scope.environment,
                actions = setOf(request.action),
                target = request.target,
                payload = request,
            ),
            ttlMillis = TICKET_TTL_MILLIS,
            reusable = false,
            available = available,
        )
    }

    /**
     * The synchronous half of the gate. HTTP callers cannot mint, renew or widen a ticket, including
     * with the legacy bridge key, and can only reach the two non-privileged actions.
     */
    internal fun handle(token: String?, body: String): Response = when (val claim = claim(token, body)) {
        is Claim.Failed -> claim.response
        is Claim.Ready -> settle(claim, bounded {
            // No shell command, path or arbitrary executor is accepted. Text was bound at issuance.
            when (claim.request.action) {
                "host.app-info" -> appInfo()
                "host.clipboard-write" -> { clipboardWrite(requireNotNull(claim.request.text)); "{\"copied\":true}" }
                else -> error("Unsupported operation")
            }
        })
    }

    /**
     * The privileged half: install and logcat need the coroutine-capable ADB channel, so they share
     * [claim] and [settle] with [handle] but never become reachable over HTTP. Cancellation is
     * rethrown so a revoked page stops the ADB call instead of getting a fake failure.
     */
    internal suspend fun handlePrivileged(token: String?, body: String): Response =
        when (val claim = claim(token, body)) {
            is Claim.Failed -> claim.response
            is Claim.Ready -> settle(claim, bounded {
                when (claim.request.action) {
                    "host.install-apk" -> requireNotNull(installApkFile)(requireNotNull(claim.request.text))
                    "host.logcat" -> requireNotNull(captureLogcat)()
                    else -> error("Unsupported operation")
                }
            })
        }

    /** Consume the one-shot ticket and prove the body matches its frozen binding exactly. */
    private fun claim(token: String?, body: String): Claim {
        val ticket = authority.begin(token) ?: return Claim.Failed(denied())
        if (!authority.valid(ticket)) return Claim.Failed(denied())
        if (!boundedRequest(body)) return Claim.Failed(Response(400, "{\"error\":\"invalid_request\"}"))
        val request = runCatching { json.decodeFromString<PluginHostRequest>(body) }.getOrNull()
            ?: return Claim.Failed(Response(400, "{\"error\":\"invalid_request\"}"))
        if (request != ticket.binding.payload) return Claim.Failed(denied())
        if (!authority.valid(ticket)) return Claim.Failed(denied())
        return Claim.Ready(request, ticket)
    }

    /** The owner must still be live and the answer stays bounded. */
    private fun settle(claim: Claim.Ready, result: Result<String>): Response {
        val output = result.getOrElse { return Response(500, "{\"error\":\"execution_failed\"}") }
        if (!authority.valid(claim.ticket)) return denied()
        if (output.length > MAX_RESULT_CHARS) return Response(500, "{\"error\":\"result_too_large\"}")
        return Response(200, output)
    }

    /** Cancellation must survive the failure funnel instead of being reported as execution_failed. */
    private inline fun bounded(block: () -> String): Result<String> = try {
        Result.success(block())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private sealed interface Claim {
        data class Ready(val request: PluginHostRequest, val ticket: HostCredentialAuthority.Ticket) : Claim
        data class Failed(val response: Response) : Claim
    }

    internal fun revoke(token: String) = authority.revoke(token)

    internal fun route(request: HostBridgeHttp.HttpRequest): Response? {
        if (request.path != "/api/plugin/call") return null
        if (request.method != "POST") return Response(404, "{\"error\":\"not_found\"}")
        val auth = request.headers["authorization"].orEmpty()
        return handle(if (auth.startsWith("Bearer ")) auth.removePrefix("Bearer ") else null, request.body)
    }

    fun revokeAll() = authority.revokeAll()

    private fun supported(request: PluginHostRequest): Boolean = when (request.action) {
        "host.app-info" -> request.target == "application" && request.text == null
        "host.clipboard-write" -> request.target == "clipboard" && request.text != null &&
            request.text.length in 1..1024 && '\u0000' !in request.text
        "host.install-apk" -> request.target == "apk" && request.text != null && validApkPath(request.text)
        "host.logcat" -> request.target == "logcat" && request.text == null
        else -> false
    }

    /**
     * Shape check only; the host still resolves the file inside its own sandbox roots, so a page can
     * never name a path the host would refuse and only an `.apk` below a known root is installable.
     */
    private fun validApkPath(path: String): Boolean {
        if (path.length !in 1..MAX_APK_PATH_LENGTH || !APK_PATH.matches(path)) return false
        val segments = path.removePrefix("/").split('/')
        return segments.size >= 2 && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** The envelope has eight fields, optionally text, and two object levels. Bound before decoding. */
    private fun boundedRequest(body: String): Boolean {
        if (body.length > 4096) return false
        var depth = 0
        var fields = 0
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 2) return false
                '}', ']' -> if (--depth < 0) return false
                ':' -> if (++fields > 9) return false
            }
        }
        if (quoted || depth != 0 || fields !in 8..9) return false
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            fields == root.size + root.getValue("scope").jsonObject.size
        }.getOrDefault(false)
    }

    private fun denied() = Response(401, "{\"error\":\"plugin_authorization_denied\"}")

    private companion object {
        const val TICKET_TTL_MILLIS = 10_000L
        const val MAX_RESULT_CHARS = 32_768
        const val MAX_APK_PATH_LENGTH = 256
        val APK_PATH = Regex(
            "/(?:sdcard|workspace|attachments|storage/emulated/0)/[A-Za-z0-9_./+-]+\\.apk",
            RegexOption.IGNORE_CASE,
        )
    }
}
