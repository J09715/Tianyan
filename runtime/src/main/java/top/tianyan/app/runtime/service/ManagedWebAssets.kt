package top.tianyan.app.runtime.service

import java.net.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native registration only. Port, paths, hashes and credentials never come from a Web message. */
class ManagedWebAssets(
    private val port: Int,
    private val prefix: String,
    hashes: Map<String, String>,
    private val authorization: String,
    val isAvailable: () -> Boolean,
    val release: suspend () -> Boolean,
) {
    private val hashes = hashes.toMap()
    init {
        require(port in 1..65535 && prefix.matches(Regex("/[A-Za-z0-9_-]{1,128}/")))
        require(authorization.length in 1..256 && authorization.none(Char::isISOControl))
        require(this.hashes.size in 1..63 && "index.html" in this.hashes)
        require(this.hashes.all { (path, hash) -> safePath(path) && hash.matches(Regex("[0-9a-f]{64}")) })
    }

    /** All bytes are fetched and verified before a document can be created. */
    suspend fun snapshot(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        withTimeout(10_000) {
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
                .followSslRedirects(false).retryOnConnectionFailure(false).protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val result = linkedMapOf<String, ByteArray>()
                var remaining = 2 * 1024 * 1024
                for ((path, hash) in hashes) {
                    coroutineContext.ensureActive()
                    check(isAvailable()) { "Web service unavailable" }
                    val request = Request.Builder().url("http://127.0.0.1:$port$prefix$path")
                        .header("Authorization", authorization).header("Accept-Encoding", "identity").build()
                    val bytes = fetch(client.newCall(request), remaining)
                    check(digest(bytes) == hash) { "Web service content mismatch" }
                    remaining -= bytes.size
                    result[path] = bytes
                }
                check(isAvailable()) { "Web service unavailable" }
                result
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun fetch(call: Call, maximum: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(IOException("Web service request failed")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.code == 200 && it.header("Content-Encoding").let { encoding -> encoding == null || encoding == "identity" })
                        val body = requireNotNull(it.body)
                        check(body.contentLength() <= maximum)
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(output.size().toLong() + count <= maximum) { "Web service assets too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    continuation.resume(bytes)
                } catch (_: Exception) { continuation.resumeWithException(IOException("Web service response rejected")) }
            }
        })
    }

    companion object {
        internal fun safePath(path: String) = path.length in 1..256 && path != "theme.css" &&
            path.split('/').let { it.size <= 16 && it.all { part -> part.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) } }
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
