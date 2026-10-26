package top.tianyan.app.harness.bot

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import top.tianyan.app.core.database.repository.TaskBudgetRepository
import top.tianyan.app.core.model.bot.TaskBudgetExceededException
import top.tianyan.app.harness.ApiProtocol
import top.tianyan.app.harness.ChatResult
import top.tianyan.app.harness.ModelConfig

/** Host-only accounting attribution for compaction calls without a sessionKey parameter. */
class TaskBudgetContext(val sessionId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskBudgetContext>
}

class TaskBudgetGuard(private val budgets: TaskBudgetRepository) {
    internal suspend fun run(sessionKey: String?, model: ModelConfig, client: OkHttpClient,
        send: suspend (ModelConfig, OkHttpClient) -> ChatResult): ChatResult {
        val session = sessionKey ?: currentCoroutineContext()[TaskBudgetContext]?.sessionId
        if (session == null || budgets.forSession(session) == null) return send(model, client)
        val initial = requireNotNull(budgets.forSession(session))
        val capped = if (initial.limitTokens > 0) model.copy(maxTokens = (model.maxTokens ?: DEFAULT_OUTPUT).coerceAtLeast(1)) else model
        val scope = RequestScope(session, capped)
        var responseReceived = false
        return try {
            val result = send(capped, client.newBuilder().addNetworkInterceptor(scope).build())
            responseReceived = true
            val usage = result.usage
            val known = (usage.inputTokens > 0 || usage.cacheReadTokens > 0 || usage.cacheWriteTokens > 0) && usage.outputTokens > 0
            val total = usage.inputTokens.coerceAtLeast(0) + usage.outputTokens.coerceAtLeast(0) +
                if (model.protocol == ApiProtocol.ANTHROPIC) usage.cacheReadTokens.coerceAtLeast(0) + usage.cacheWriteTokens.coerceAtLeast(0) else 0
            withContext(NonCancellable) { scope.finish(if (known) total else null) }
            result
        } catch (failure: Throwable) {
            val accounting = withContext(NonCancellable) { runCatching { scope.finish(null) }.exceptionOrNull() }
            if (accounting != null || responseReceived) throw IllegalStateException("模型结果或预算结算需核对，禁止自动重发", failure)
            val limit = generateSequence(failure) { it.cause }.filterIsInstance<TaskBudgetExceededException>().firstOrNull()
            if (limit != null) {
                withContext(NonCancellable) { budgets.block(session, limit.message.orEmpty()) }
                throw limit
            }
            throw failure
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private inner class RequestScope(val session: String, val model: ModelConfig) : Interceptor {
        private val reservations = mutableListOf<Pair<String, Long>>()
        private val successful = mutableSetOf<String>()
        private var finished = false
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body ?: throw TaskBudgetExceededException("预算模式无法核对无请求体的模型调用")
            check(!body.isDuplex() && !body.isOneShot()) { "无法核对一次性模型请求体的预算" }
            val buffer = Buffer()
            body.writeTo(buffer)
            val size = buffer.size
            val output = runCatching {
                val limits = budgetJson.decodeFromStream<OutputCaps>(buffer.inputStream())
                listOf(limits.max_tokens, limits.max_completion_tokens, limits.max_output_tokens).firstOrNull { it != null && it > 0 }
            }.getOrNull()
            val state = runBlocking(Dispatchers.IO) { budgets.forSession(session) } ?: error("任务预算归属已删除")
            if (state.limitTokens > 0 && output == null) throw TaskBudgetExceededException("预算模式要求模型请求包含明确的输出上限")
            // Reserve serialized UTF-8 bytes plus output cap, rather than an optimistic text-token estimate.
            val tokens = Math.addExact(size + 256, output ?: model.maxTokens?.toLong() ?: DEFAULT_OUTPUT.toLong())
            val id = UUID.randomUUID().toString()
            check(runBlocking(Dispatchers.IO) { budgets.reserve(session, id, tokens) }) { "原任务预算归属已失效" }
            synchronized(reservations) { reservations += id to tokens }
            val response = chain.proceed(request)
            if (response.isSuccessful) synchronized(reservations) { successful += id }
            return response
        }

        suspend fun finish(actual: Long?) {
            if (finished) return
            // Settle actual usage first, then release remaining reservations: interruption can only over-reserve.
            var assigned = false
            synchronized(reservations) { reservations.toList() }.forEach { (id, reserved) ->
                val known = actual != null && id in successful
                budgets.settle(id, if (!known) reserved else if (!assigned) requireNotNull(actual) else 0, known)
                if (known) assigned = true
            }
            finished = true
        }
    }

    @Serializable private data class OutputCaps(val max_tokens: Long? = null,
        val max_completion_tokens: Long? = null, val max_output_tokens: Long? = null)
    companion object {
        const val DEFAULT_OUTPUT = 8192
        private val budgetJson = Json { ignoreUnknownKeys = true }
    }
}
