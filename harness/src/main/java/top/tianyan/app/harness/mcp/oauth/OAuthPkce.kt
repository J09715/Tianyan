package top.tianyan.app.harness.mcp.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import android.net.Uri
import java.net.URI

/** Pure OAuth PKCE/state helpers. No token or callback data is logged or persisted here. */
object OAuthPkce {
    private val random = SecureRandom()

    fun randomState(): String = randomBytes(32)
    fun codeVerifier(): String = randomBytes(32)

    fun challenge(verifier: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun matchesState(expected: String, actual: String): Boolean {
        val left = expected.toByteArray(Charsets.UTF_8)
        val right = actual.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(left, right)
    }

    private fun randomBytes(size: Int): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Fixed callback policy. Keep this narrow because custom schemes can be claimed by other apps. */
data class OAuthRedirectPolicy(
    val scheme: String,
    val host: String,
    val path: String,
) {
    /** String/URI based so the callback policy remains testable on the local JVM. */
    fun accepts(uri: String): Boolean = runCatching {
        val parsed = URI(uri)
        parsed.scheme == scheme && parsed.host == host && parsed.path == path
    }.getOrDefault(false)
}

object OAuthEndpointPolicy {
    fun requireSecure(url: String): String {
        val uri = URI(url)
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val loopback = uri.scheme.equals("http", ignoreCase = true) && uri.host?.lowercase() in
            setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(secure || loopback) { "OAuth endpoint 必须使用 HTTPS；仅允许 loopback 使用 HTTP" }
        require(!uri.host.isNullOrBlank()) { "OAuth endpoint 缺少 host" }
        return url
    }
}

fun Uri.oauthState(): String? = getQueryParameter("state")
fun Uri.oauthCode(): String? = getQueryParameter("code")
fun Uri.oauthError(): String? = getQueryParameter("error")
