package top.tianyan.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.repository.BtwQueryRepository
import top.tianyan.app.core.model.BtwQuery
import top.tianyan.app.core.model.BtwStatus
import top.tianyan.app.harness.btw.BtwService

/**
 * /btw 旁路问答的会话内状态与执行：回答不打断当前回合、不进入模型上下文，
 * 卡片只给用户看。挂在 [ChatViewModel] 下按当前会话观察记录。
 */
class BtwChatController(
    private val service: BtwService?,
    private val repository: BtwQueryRepository?,
) {
    companion object {
        val COMMAND = Regex("^/btw\\s+(.+?)\\s*\$")
    }

    private val _queries = MutableStateFlow<List<BtwQuery>>(emptyList())
    val queries: StateFlow<List<BtwQuery>> = _queries.asStateFlow()

    /** 正在回答的旁路问题文本（流式）。 */
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    private val active = MutableStateFlow(false)
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var sessionIds: StateFlow<String>? = null
    private var notify: (String) -> Unit = {}

    /** 由 ViewModel 在初始化时挂接作用域与当前会话流；未注入服务时静默禁用。 */
    fun start(scope: CoroutineScope, sessionIds: StateFlow<String>, onNotice: (String) -> Unit) {
        this.scope = scope
        this.sessionIds = sessionIds
        notify = onNotice
        val repo = repository ?: return
        scope.launch {
            sessionIds.collectLatest { sessionId ->
                if (sessionId.isBlank()) {
                    _queries.value = emptyList()
                    return@collectLatest
                }
                combine(repo.observeForSession(sessionId), active) { records, isActive -> records to isActive }
                    .collect { (records, isActive) ->
                        // RUNNING 但没有在跑任务的记录是进程重启遗留，收割成失败而不是永远转圈。
                        val stale = records.filter { it.isRunning && !isActive }
                        if (stale.isNotEmpty()) {
                            stale.forEach {
                                repo.save(
                                    it.copy(
                                        status = BtwStatus.FAILED,
                                        error = "已被中断（进程重启）",
                                        finishedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            return@collect
                        }
                        _queries.value = records
                    }
            }
        }
    }

    /** 发起旁路问答：忙与空闲都可用；已有进行中的问答时提示并忽略。 */
    fun submit(question: String) {
        val currentScope = scope
        val currentService = service
        val sessionId = sessionIds?.value.orEmpty()
        if (currentScope == null || currentService == null || question.isBlank() || sessionId.isBlank()) return
        if (job?.isActive == true) {
            notify("已有一个旁路问答正在进行，请先等它结束或取消")
            return
        }
        job = currentScope.launch {
            active.value = true
            try {
                currentService.ask(
                    sessionId = sessionId,
                    rawQuestion = question.trim(),
                    onReset = { _streaming.value = "" },
                    onDelta = { _streaming.value += it },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notify("旁路问答失败：${failure.message ?: "未知错误"}")
            } finally {
                active.value = false
                _streaming.value = ""
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
