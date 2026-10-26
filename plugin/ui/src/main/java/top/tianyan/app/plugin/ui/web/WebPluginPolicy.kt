package top.tianyan.app.plugin.ui.web

import java.net.URI
import java.util.UUID

/** Unique origin per document prevents storage/service-worker reuse across plugins and versions. */
class WebPluginPolicy(val origin: String = "https://${UUID.randomUUID()}.plugin.tianyan.invalid") {
    init {
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.rawAuthority == uri.host && uri.rawPath.isEmpty())
    }

    fun assetPath(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || "https://${uri.rawAuthority}" != origin || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.removePrefix("/")
        path.takeIf { it.length in 1..256 && it.split('/').all { part ->
            part.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) && part != "." && part != ".."
        } }
    }.getOrNull()

    fun canNavigate(url: String): Boolean = assetPath(url) == "index.html"

    companion object {
        const val CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; " +
            "connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}
