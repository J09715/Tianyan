package top.tianyan.app.harness.btw

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessUsageEntity
import top.tianyan.app.core.database.repository.BtwQueryRepository
import top.tianyan.app.core.model.BtwQuery
import top.tianyan.app.core.model.BtwStatus
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ChatResult
import top.tianyan.app.harness.ChatUsage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.TextToolCallCodec

/** 会话实体只读端口。 */
fun interface BtwSessionLookup {
    suspend fun session(sessionId: String): HarnessSessionEntity?
}

/** 按会话绑定的档案解析模型配置（与主循环同口径）。 */
fun interface BtwModelResolver {
    suspend fun resolve(session: HarnessSessionEntity): ModelConfig
}

/** 组装与主循环请求同构的会话转录（system + 历史 + recall；悬空工具调用自动跳过）。 */
fun interface BtwTranscriptSource {
    suspend fun transcript(session: HarnessSessionEntity, model: ModelConfig): List<ApiMessage>
}

/** 单次旁路模型调用（流式文本写入 [text]）。 */
fun interface BtwModelCaller {
    suspend fun call(model: ModelConfig, messages: List<ApiMessage>, sessionKey: String, text: StringBuilder): ChatResult
}

/** 用量落账端口（旁路调用没有 operation，只记 harness_usage 流水）。 */
fun interface BtwUsageSink {
    suspend fun record(usage: HarnessUsageEntity)
}

/**
 * 旁路问答（/btw）：Bot 忙碌或空闲时，基于当前会话已提交进展的"顺便问一句"。
 *
 * 借鉴 DS Bot 的 /btw 设计：
 * - 只读：快照转录 + 末尾追加一条旁路问题，不写入模型可见历史与 Bot 记忆；
 * - 同构请求：同一模型、同一 system、同一工具声明（保持前缀缓存命中），
 *   仅靠提示约束"不要调用工具"；模型执意调用则重试一次，再犯判失败；
 * - 答案只进用户可见卡片（[BtwQuery]），可能落后于实际进展，卡片注明。
 */
class BtwService(
    private val sessions: BtwSessionLookup,
    private val modelResolver: BtwModelResolver,
    private val transcriptSource: BtwTranscriptSource,
    private val modelCaller: BtwModelCaller,
    private val usageSink: BtwUsageSink,
    private val repository: BtwQueryRepository,
    private val json: Json,
) {

    /**
     * 发起一次旁路问答并落库。返回终态记录（COMPLETED / FAILED）；用户取消时
     * 落库 CANCELLED 后原样抛出 [CancellationException]。
     */
    suspend fun ask(
        sessionId: String,
        rawQuestion: String,
        onReset: () -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): BtwQuery {
        val question = rawQuestion.trim()
        require(question.isNotBlank()) { "请在 /btw 后输入问题" }
        val session = sessions.session(sessionId)
            ?: return failed(sessionId, question, "会话不存在或已删除")
        var record = BtwQuery(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            question = question,
            createdAt = System.currentTimeMillis(),
        )
        repository.save(record)
        return try {
            val model = modelResolver.resolve(session)
            record = record.copy(modelId = model.model, providerId = model.provider)
            val transcript = transcriptSource.transcript(session, model)
            if (transcript.none { it.role == "assistant" }) {
                finish(record, BtwStatus.FAILED, error = "会话还没有可参考的进展，先正常对话一次再使用旁路问答")
            } else {
                var messages = transcript + btwQuestionMessage(question)
                var text = StringBuilder()
                var result = modelCaller.call(model, messages, sessionId, text)
                if (result.toolCalls.isNotEmpty()) {
                    // 模型无视了"不要调用工具"：重试一次（DS Bot 同款），再犯即失败。
                    onReset()
                    text = StringBuilder()
                    messages = messages + ApiMessage(role = "user", content = RETRY_NOTE)
                    result = modelCaller.call(model, messages, sessionId, text)
                    check(result.toolCalls.isEmpty()) { "模型仍尝试调用工具，旁路问答已终止" }
                }
                val raw = text.toString().ifBlank { result.content.orEmpty() }
                val normalization = TextToolCallCodec.normalize(json, raw)
                val answer = (if (normalization.hasMarkers) normalization.displayText else raw).trim()
                finish(record, BtwStatus.COMPLETED, answer = answer, usage = result.usage)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                repository.save(
                    record.copy(
                        answer = "",
                        status = BtwStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            finish(record, BtwStatus.FAILED, error = failure.message ?: "旁路问答请求失败")
        }
    }

    private suspend fun failed(sessionId: String, question: String, error: String): BtwQuery {
        val record = BtwQuery(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            question = question,
            status = BtwStatus.FAILED,
            error = error,
            createdAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
        )
        repository.save(record)
        return record
    }

    private suspend fun finish(
        record: BtwQuery,
        status: BtwStatus,
        answer: String = "",
        error: String = "",
        usage: ChatUsage? = null,
    ): BtwQuery {
        val finished = record.copy(
            answer = answer,
            status = status,
            error = error,
            finishedAt = System.currentTimeMillis(),
            inputTokens = usage?.inputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0,
            cachedTokens = usage?.cacheReadTokens ?: 0,
        )
        repository.save(finished)
        if (usage != null && usage.hasData && status == BtwStatus.COMPLETED) {
            usageSink.record(
                HarnessUsageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = finished.sessionId,
                    operationId = OPERATION_ID,
                    entryId = null,
                    provider = finished.providerId,
                    modelId = finished.modelId,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    reasoningTokens = usage.reasoningTokens,
                    cacheReadTokens = usage.cacheReadTokens,
                    cacheWriteTokens = usage.cacheWriteTokens,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return finished
    }

    private companion object {
        const val OPERATION_ID = "btw"
        const val RETRY_NOTE = "刚才的请求仍被解读为要调用工具。请直接用文字回答上面的问题，不要调用任何工具。"

        fun btwQuestionMessage(question: String) = ApiMessage(
            role = "user",
            content = "【旁路问答】用户趁你不打断当前工作顺便问一句（答案只给用户看，不进入你的会话记录，" +
                "也不会影响你正在进行的工作）：\n$question\n\n" +
                "请只依据以上对话已有进展直接回答：不要调用任何工具、不要执行操作、不要展开新任务；" +
                "保持简洁；若答案可能落后于当前实际进展，请说明。",
        )
    }
}
