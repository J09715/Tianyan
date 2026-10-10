package top.tianyan.app.harness.question

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.AgentQuestion
import top.tianyan.app.core.model.AgentQuestionAnswer
import top.tianyan.app.core.model.AgentQuestionResponse
import top.tianyan.app.core.model.isAnswered
import top.tianyan.app.core.model.normalized

/** 一次挂起中的提问：UI 需要的问题列表 + 供作答定位的标识。 */
@Serializable
data class PendingQuestion(
    val callId: String,
    val sessionId: String,
    val questions: List<AgentQuestion>,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 智能体提问的中枢：工具侧挂起等待，UI 侧作答唤醒。
 *
 * 沿用 [top.tianyan.app.harness.workflow.WorkflowApprovalBroker] 已验证的
 * 「CompletableDeferred + StateFlow」模式，但补两点 ask_user_question 特有的需求：
 *
 *  1. **同一时刻只允许一个问题卡片**：多轮并发提问会让 UI 无法归属，因此
 *     新请求在已有挂起请求时直接失败，由模型改在下一次调用询问；
 *  2. **作答必须能跨进程死亡恢复**：调用方在挂起重试后拿到的 callId 可能已失效，
 *     所以 [submit] 对未知 callId 返回 false 而非抛异常，由上层决定是否降级。
 *
 * 取消语义：会话取消时唤醒挂起的提问并让其以「已跳过」结束，
 * 而不是让协程永久悬挂——否则取消后台线程会泄漏。
 */
@Singleton
class AgentQuestionBroker @Inject constructor(
    private val json: Json,
) {
    private val waiting = linkedMapOf<String, CompletableDeferred<AgentQuestionResponse>>()

    private val _pending = MutableStateFlow<PendingQuestion?>(null)
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()

    /**
     * 挂起等待用户作答。返回后 [PendingQuestion] 自动出队，
     * 界面无需再手动清理。
     */
    suspend fun ask(
        sessionId: String,
        callId: String,
        questions: List<AgentQuestion>,
    ): AgentQuestionResponse {
        val deferred = CompletableDeferred<AgentQuestionResponse>()
        synchronized(waiting) {
            check(waiting.isEmpty()) {
                "已有待作答的提问（callId=${_pending.value?.callId}）；" +
                    "同一时刻只支持一个问题卡片，请等用户作答后再提问。"
            }
            waiting[callId] = deferred
            _pending.value = PendingQuestion(callId = callId, sessionId = sessionId, questions = questions)
        }
        return try {
            deferred.await()
        } finally {
            synchronized(waiting) {
                waiting.remove(callId)
                _pending.value = null
            }
        }
    }

    /**
     * 提交作答。返回 true 表示成功唤醒挂起的工具调用；
     * false 表示该 callId 已失效（重复提交 / 已取消 / 进程重启后重放）。
     */
    fun submit(callId: String, response: AgentQuestionResponse): Boolean =
        synchronized(waiting) { waiting[callId]?.complete(response) == true }

    /** 按问题定义补全并规范化作答，供 UI 与工具侧共用。 */
    fun normalize(questions: List<AgentQuestion>, answers: List<AgentQuestionAnswer>): AgentQuestionResponse {
        val byId = answers.associateBy { it.id }
        return AgentQuestionResponse(
            answers = questions.map { question ->
                val raw = byId[question.id] ?: AgentQuestionAnswer(id = question.id, skipped = true)
                val filled = if (raw.isAnswered()) raw else raw.copy(skipped = true)
                filled.normalized(question.multiSelect)
            },
        )
    }

    /** 会话取消 / 界面关闭：唤醒挂起提问并以「全部跳过」结束，避免协程泄漏。 */
    fun cancel(callId: String): Boolean = synchronized(waiting) {
        val deferred = waiting[callId] ?: return false
        val pending = _pending.value
        val questions = pending?.takeIf { it.callId == callId }?.questions.orEmpty()
        deferred.complete(AgentQuestionResponse(answers = questions.map { AgentQuestionAnswer(id = it.id, skipped = true) }))
        true
    }

    /** 工具回传给模型的文本：紧凑 JSON，与 Harness 的 render 一致。 */
    fun render(response: AgentQuestionResponse): String =
        json.encodeToString(AgentQuestionResponse.serializer(), response)

    /** 测试与诊断用：当前是否有挂起提问。 */
    fun hasPending(): Boolean = synchronized(waiting) { waiting.isNotEmpty() }
}
