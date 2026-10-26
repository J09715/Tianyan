package top.tianyan.app.harness.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okio.Buffer
import top.tianyan.app.harness.ProviderRequestBudget

/** Observe the final protocol body, including JSON_TEXT schemas and transport fallbacks. */
internal fun OkHttpClient.withRequestDiagnostics(
    onRequest: ((String, String, Long, Collection<String>) -> Unit)?,
): OkHttpClient {
    if (onRequest == null) return this
    val observer = Interceptor { chain ->
        val request = chain.request()
        // Diagnostics must never prevent the request from running. No raw body is logged.
        runCatching {
            val body = request.body
            if (body != null && !body.isDuplex() && !body.isOneShot() && body.contentLength() in 0..MAX_CAPTURE_BYTES) {
                val buffer = Buffer()
                body.writeTo(buffer)
                val bytes = buffer.size
                val credentials = request.headers.names().filter {
                    it.contains("key", true) || it.contains("token", true) || it.contains("secret", true) ||
                        it.equals("Authorization", true) || it.equals("Cookie", true)
                }.flatMap { name -> request.headers.values(name).flatMap { listOf(it, it.removePrefix("Bearer ")) } }
                onRequest(request.url.encodedPath.substringAfterLast('/'), buffer.readUtf8(), bytes, credentials)
            }
        }
        chain.proceed(request)
    }
    return newBuilder().apply {
        // Capture constructed-but-rejected requests too, after caller interceptors and before admission.
        val guard = interceptors().indexOfFirst { it is ProviderRequestBudget }
        interceptors().add(if (guard < 0) interceptors().size else guard, observer)
    }.build()
}

private const val MAX_CAPTURE_BYTES = 24_000_000L
