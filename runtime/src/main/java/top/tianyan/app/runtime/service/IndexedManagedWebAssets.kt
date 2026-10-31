package top.tianyan.app.runtime.service

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * Native registration only; port, base path and authorization never come from a Web message.
 *
 * Unlike [ManagedWebAssets] the file set of a live service is unknown up front, so this source
 * pins the digest of the first response of every path it serves. The pinned digest is then the
 * only accepted content for that path for the rest of the session: a later response with different
 * bytes, more than 63 paths or an over-budget tree closes the whole source. Presented to the
 * WebView as the same bounded snapshot contract; content and credentials still never enter Web
 * content, and no host credential is issued here.
 */
class IndexedManagedWebAssets(
    private val port: Int,
    private val basePath: String,
    private val authorization: String? = null,
    private val available: () -> Boolean,
    private val indexPath: String = ENTRY_PATH,
    private val authentication: ToolServiceAuthentication? = null,
) {
    private class Pinned(val digest: String, val bytes: ByteArray)

    private val pinned = mutableMapOf<String, Pinned>()
    private val monitor = Any()
    @Volatile private var drifted = false
    @Volatile private var closed = false
    private var total = 0L
    private var client: OkHttpClient? = null

    init {
        require(port in 1..65535)
        require(validBasePath(basePath)) { "Web service base path invalid" }
        require(authorization == null || (authorization.length in 1..256 && authorization.none(Char::isISOControl)))
        require(ManagedWebAssets.safePath(indexPath)) { "Web service entry path invalid" }
    }

    /** False as soon as the backing service is gone or any served path drifted from its pin. */
    val isAvailable: Boolean get() = !closed && !drifted && runCatching(available).getOrDefault(false)

    /** A document can only be created after the entry document byte-for-byte matches its pin. */
    suspend fun snapshot(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        val entry = requireNotNull(resolve(indexPath)) { "Web service entry is unavailable" }
        mapOf(indexPath to entry)
    }

    /**
     * Bounded blocking read used from the WebView resource thread. Returns null (which the caller
     * maps to a fixed 403) whenever the path, the service or the pinned content no longer holds.
     */
    fun resolve(path: String): ByteArray? {
        if (!ManagedWebAssets.safePath(path) || !isAvailable) return null
        val bytes = try { fetch(path) } catch (_: Exception) { return null }
        return synchronized(monitor) {
            if (closed || drifted) return null
            val digest = ManagedWebAssets.digest(bytes)
            val known = pinned[path]
            when {
                known == null && (pinned.size >= MAX_FILES || total + bytes.size > MAX_TOTAL_BYTES) -> {
                    drifted = true
                    null
                }
                known == null -> {
                    pinned[path] = Pinned(digest, bytes.copyOf())
                    total += bytes.size
                    bytes
                }
                known.digest == digest -> bytes
                else -> {
                    drifted = true
                    null
                }
            }
        }
    }

    suspend fun close(): Boolean = withContext(Dispatchers.IO) {
        val active = synchronized(monitor) {
            closed = true
            pinned.clear()
            total = 0
            client
        }
        client = null
        active?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        true
    }

    private fun fetch(path: String): ByteArray {
        val request = Request.Builder().url("http://127.0.0.1:$port$basePath$path")
            .header("Accept-Encoding", "identity")
        authorization?.let { request.header("Authorization", it) }
        return client().newCall(request.build()).execute().use { response ->
            check(response.code == 200) { "Web service response rejected" }
            check(response.header("Content-Encoding").let { it == null || it == "identity" })
            val body = requireNotNull(response.body)
            check(body.contentLength() <= MAX_TOTAL_BYTES) { "Web service asset too large" }
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size().toLong() + count <= MAX_TOTAL_BYTES) { "Web service asset too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun client(): OkHttpClient = synchronized(monitor) {
        client ?: OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .apply { authentication?.let { addInterceptor(it) } }
            .followSslRedirects(false).retryOnConnectionFailure(false).protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS).build().also { client = it }
    }

    companion object {
        const val ENTRY_PATH = "index.html"
        private const val MAX_FILES = 64
        private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024

        /** Native base paths are plain, absolute and traversal-free: "/", "/ui/", "/a/b/". */
        internal fun validBasePath(path: String) =
            path == "/" || path.matches(Regex("(/[A-Za-z0-9_-]{1,128})+/"))
    }
}
