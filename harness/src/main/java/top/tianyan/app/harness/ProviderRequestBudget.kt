package top.tianyan.app.harness

import okhttp3.Interceptor
import okhttp3.Response

/** Checks the serialized protocol body, including tools and opaque Responses replay data. */
internal class ProviderRequestBudget(
    private val limitBytes: Long = ContextWindowPolicy.REQUEST_BODY_HARD_LIMIT_BYTES.toLong(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        request.body?.let { body ->
            val bytes = body.contentLength()
            if (bytes < 0) throw IllegalStateException("无法确认模型请求体积，已停止发送")
            if (bytes > limitBytes) throw LlmContextOverflowException(
                "模型请求序列化后为 $bytes 字节，超过本地发送上限 $limitBytes 字节")
        }
        return chain.proceed(request)
    }
}
