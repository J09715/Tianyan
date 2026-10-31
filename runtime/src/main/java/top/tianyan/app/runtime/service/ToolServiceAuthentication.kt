package top.tianyan.app.runtime.service

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Per-process key shared only over the process input pipe. Never an environment or page value. */
class ToolServiceAuthentication private constructor(private val key: ByteArray) : Interceptor {
    constructor() : this(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun bootstrap(): ByteArray = ("{\"protocol\":\"tianyan-service-v1\",\"key\":\"${hex(key)}\"}\n")
        .toByteArray(Charsets.UTF_8)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val body = Buffer().also { original.body?.writeTo(it) }.readByteArray()
        val nonce = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = sign("request\n$timestamp\n$nonce\n${original.method}\n${original.url.encodedPath}\n${digest(body)}")
        val request = original.newBuilder().header(HEADER, "$timestamp:$nonce:$signature").build()
        return chain.proceed(request).use { response ->
            val bytes = response.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
            if (bytes.size > MAX_RESPONSE_BYTES || response.header(RESPONSE_HEADER) !=
                sign("response\n$nonce\n${response.code}\n${digest(bytes)}")) {
                throw IOException("Service authentication failed")
            }
            response.newBuilder().body(bytes.toResponseBody(response.body?.contentType())).build()
        }
    }

    private fun sign(value: String): String = hex(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    })

    companion object {
        const val HEADER = "X-Taixu-Service-Authorization"
        const val RESPONSE_HEADER = "X-Taixu-Service-Response"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
