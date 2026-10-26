package top.tianyan.app.runtime.service

import java.io.InputStreamReader
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/**
 * Native-only bounded reader over the page's own local service.
 *
 * Unlike [IndexedManagedWebAssets] nothing is pinned: a live API answer may legitimately change, so
 * only the path, the single-shot text budget and the running stream budget are bounded. Port, base
 * path and any authorization still come from native state, never from a Web message, and the
 * document never learns the loopback address. Cancellation is observed at every read checkpoint and
 * the caller rethrows it, so a revoked page stops the request instead of swallowing the failure.
 */
class ManagedWebServiceClient(
    private val port: Int,
    private val basePath: String,
    private val authorization: String? = null,
    private val available: () -> Boolean,
    private val maxResponseChars: Int = MAX_RESPONSE_CHARS,
    private val maxStreamChars: Int = MAX_STREAM_CHARS,
    private val chunkChars: Int = CHUNK_CHARS,
) {
    private val monitor = Any()
    @Volatile private var closed = false
    private var client: OkHttpClient? = null

    init {
        require(port in 1..65535) { "Web service port invalid" }
        require(IndexedManagedWebAssets.validBasePath(basePath)) { "Web service base path invalid" }
        require(authorization == null || (authorization.length in 1..256 && authorization.none(Char::isISOControl))) {
            "Web service authorization invalid"
        }
        require(maxResponseChars in 1..MAX_RESPONSE_CHARS) { "Web service response budget invalid" }
        require(chunkChars in 64..CHUNK_CHARS) { "Web service chunk size invalid" }
        require(maxStreamChars in chunkChars..MAX_STREAM_CHARS) { "Web service stream budget invalid" }
    }

    /** False as soon as the source is closed or the backing service is gone. */
    val isAvailable: Boolean get() = !closed && runCatching(available).getOrDefault(false)

    /** Single bounded read; null when the path, the service or the answer is not usable. */
    suspend fun read(path: String): String? {
        if (!usable(path)) return null
        return try {
            withContext(Dispatchers.IO) {
                call(path).use { response -> readChars(response, maxResponseChars) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Incremental bounded read; false stops the producer once the source is spent (bad path, dead
     * service, spent budget or a consumer that stopped accepting chunks).
     */
    suspend fun stream(path: String, emit: suspend (String) -> Boolean): Boolean {
        if (!usable(path)) return false
        return try {
            withContext(Dispatchers.IO) {
                call(path).use { response ->
                    val reader = readerOf(response)
                    try {
                        var remaining = maxStreamChars
                        val buffer = CharArray(chunkChars)
                        while (remaining > 0) {
                            coroutineContext.ensureActive()
                            val count = reader.read(buffer, 0, minOf(chunkChars, remaining))
                            if (count < 0) return@withContext true
                            remaining -= count
                            if (!emit(String(buffer, 0, count))) return@withContext false
                        }
                        false
                    } finally {
                        reader.close()
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    fun close() = synchronized(monitor) {
        closed = true
        client?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        client = null
    }

    private fun usable(path: String) = ManagedWebAssets.safePath(path) && isAvailable

    private fun call(path: String): Response {
        val request = Request.Builder().url("http://127.0.0.1:$port$basePath$path").header("Accept-Encoding", "identity")
        authorization?.let { request.header("Authorization", it) }
        val response = client().newCall(request.build()).execute()
        val encoding = response.header("Content-Encoding")
        if (response.code != 200 || (encoding != null && encoding != "identity")) {
            response.close()
            error("Web service response rejected")
        }
        return response
    }

    private suspend fun readChars(response: Response, maximum: Int): String {
        val reader = readerOf(response)
        return try {
            val buffer = CharArray(READ_CHARS)
            val output = StringBuilder()
            while (true) {
                coroutineContext.ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                check(output.length + count <= maximum) { "Web service response too large" }
                output.append(buffer, 0, count)
            }
            output.toString()
        } finally {
            reader.close()
        }
    }

    /** Fixed UTF-8 decoding keeps the host's bounds comparable across services. */
    private fun readerOf(response: Response) = InputStreamReader(requireNotNull(response.body).byteStream(), Charsets.UTF_8)

    private fun client(): OkHttpClient = synchronized(monitor) {
        check(!closed) { "Web service client closed" }
        client ?: OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .build().also { client = it }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 32_768
        const val MAX_STREAM_CHARS = 65_536
        const val CHUNK_CHARS = 1_024
        const val READ_CHARS = 4_096
    }
}
