package top.tianyan.app.harness.projection

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall

/**
 * 会话级运行状态与前台聚焦镜像：状态机（就绪/运行中/等待审批…）、动作描述、
 * 错误文案、思考中的实时指示，以及推理模型是否进入思考模式的标记。
 *
 * - 每会话一份底层存储；前台会话的值同时镜像到全局单值流供 UI / 通知消费。
 * - 纯内存组件，不持有任何业务依赖；写入语义与原 HarnessLoop 内联实现一致。
 */
class SessionStateMirrors(
    private val tracker: CurrentSessionTracker,
) {
    val providerActivity = top.tianyan.app.harness.session.ProviderActivityTracker()

    private val _sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
    /** 全局所有会话的运行状态映射（供会话抽屉、状态点等观察） */
    val sessionRunStates: StateFlow<Map<String, SessionRunState>> = _sessionRunStates.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 全局各会话当前的动作描述状态 */
    val sessionStatuses: StateFlow<Map<String, String>> = _sessionStatuses.asStateFlow()

    private val _modelOutputRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val modelOutputRevisions: StateFlow<Map<String, Long>> = _modelOutputRevisions.asStateFlow()
    fun recordModelOutput(sessionId: String, phase: String = "model_output") {
        providerActivity.output(sessionId, phase)
        _modelOutputRevisions.update { it + (sessionId to ((it[sessionId] ?: 0L) + 1)) }
    }

    private val sessionErrors = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val sessionThinkingLives = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /** 推理模型思考标记（reasoning 是否出现过），用于下一轮请求的上下文组装。 */
    private val thinkingModes = ConcurrentHashMap<String, Boolean>()

    // ---- 当前前台聚焦会话的全局响应式镜像 ----
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 点亮 [_running] 的会话 id（null = 没有会话在驱动前台运行镜像）。
     *
     * 旧实现只在 "写入终态的那一刻该会话仍在前台" 时才复位 `_running`，一旦终态写入时
     * 前台焦点已经切走（用户离开聊天页、`currentSessionId` 被清空、运行期新建/切换过会话），
     * 复位条件就永远不成立，`_running` 会永久停在 true：聊天页一直显示"执行中"、停止键
     * 看起来完全没用，只有杀进程重启（loadSession 重新按 sessionJobs 计算）才能恢复。
     * 记住"谁点亮的"后，终态复位不再依赖写入瞬间的前台焦点。
     */
    private var runningOwner: String? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _thinkingLive = MutableStateFlow(false)
    /** 推理模型思考中（reasoning 正在流式上屏）。 */
    val thinkingLive: StateFlow<Boolean> = _thinkingLive.asStateFlow()

    fun ensureFlows(sessionId: String) {
        sessionErrors[sessionId] = MutableStateFlow(null)
        sessionThinkingLives[sessionId] = MutableStateFlow(false)
    }

    fun removeSession(sessionId: String) {
        providerActivity.remove(sessionId)
        sessionErrors.remove(sessionId)
        sessionThinkingLives.remove(sessionId)
        thinkingModes.remove(sessionId)
        _sessionRunStates.update { it - sessionId }
        _sessionStatuses.update { it - sessionId }
        _modelOutputRevisions.update { it - sessionId }
        // 删掉的正是点亮前台运行镜像的会话：清掉 owner 并复位，避免 _running 永久停在 true。
        // 终态写入（setRunState / onRunFinished）走另一条路径，这里只处理会话已不存在的情况。
        if (runningOwner == sessionId) {
            runningOwner = null
            _running.value = false
        }
    }

    fun setStatus(sessionId: String, statusText: String?) {
        val normalized = statusText?.takeIf { it.isNotBlank() }
        if (_sessionStatuses.value[sessionId] == normalized) {
            if (tracker.isForeground(sessionId) && _status.value != normalized) _status.value = normalized
            return
        }
        _sessionStatuses.update { map ->
            if (normalized == null) map - sessionId else map + (sessionId to normalized)
        }
        if (tracker.isForeground(sessionId)) {
            _status.value = normalized
        }
    }

    fun lastStatus(sessionId: String): String? = _sessionStatuses.value[sessionId]

    fun setError(sessionId: String, errorText: String?) {
        errorFlowOf(sessionId).value = errorText
        if (tracker.isForeground(sessionId)) {
            _error.value = errorText
        }
    }

    private fun errorFlowOf(sessionId: String): MutableStateFlow<String?> =
        sessionErrors.getOrPut(sessionId) { MutableStateFlow(null) }

    fun errorOf(sessionId: String): String? = sessionErrors[sessionId]?.value

    /** 该会话思考指示的当前读数（未初始化视为 false）。 */
    fun thinkingLiveOf(sessionId: String): Boolean = sessionThinkingLives[sessionId]?.value ?: false

    fun setRunState(sessionId: String, state: SessionRunState) {
        _sessionRunStates.update { it + (sessionId to state) }
        if (state == SessionRunState.RUNNING) {
            // 只有前台会话的运行才点亮全局镜像，避免后台会话把当前聊天页的输入框锁住。
            if (tracker.isForeground(sessionId)) {
                runningOwner = sessionId
                _running.value = true
            }
        } else if (runningOwner == null || runningOwner == sessionId) {
            // 终态：谁点亮的谁负责复位，不要求该会话此刻仍在前台（前台焦点可能已经切走）。
            runningOwner = null
            _running.value = false
        }
    }

    fun runStateOf(sessionId: String): SessionRunState? = _sessionRunStates.value[sessionId]

    fun isWaitingApproval(sessionId: String): Boolean =
        _sessionRunStates.value[sessionId] == SessionRunState.WAITING_APPROVAL

    /**
     * 流式思考指示的开关：更新会话级 flow，并对前台会话同步全局镜像。
     */
    fun setThinkingLive(sessionId: String, live: Boolean) {
        thinkingLiveFlowOf(sessionId).value = live
        if (tracker.isForeground(sessionId)) {
            _thinkingLive.value = live
        }
    }

    /**
     * 运行收尾：折叠会话级思考指示并复位前台镜像。
     * @return 结束前是否处于 WAITING_APPROVAL（调用方据此跳过后续排队排空）。
     */
    fun onRunFinished(sessionId: String): Boolean {
        val waitingApproval = isWaitingApproval(sessionId)
        if (!waitingApproval) setStatus(sessionId, null)
        // 同上：复位按"谁点亮的"判定，不在前台也要复位，避免运行镜像永久卡在 true。
        if (runningOwner == null || runningOwner == sessionId) {
            runningOwner = null
            _running.value = false
        }
        if (tracker.isForeground(sessionId)) {
            if (!waitingApproval) _status.value = null
            _thinkingLive.value = false
        }
        sessionThinkingLives[sessionId]?.value = false
        return waitingApproval
    }

    /** loadSession 恢复前台错误镜像（无对应的空闲态不产生副作用）。 */
    fun restoreForegroundError(sessionId: String, value: String?) {
        setError(sessionId, value)
    }

    fun setForegroundRunning(active: Boolean) {
        runningOwner = if (active) tracker.foregroundId.takeIf { it.isNotBlank() } else null
        _running.value = active
    }

    fun resetForeground() {
        runningOwner = null
        _running.value = false
        _error.value = null
        _status.value = null
        _thinkingLive.value = false
    }

    private fun thinkingLiveFlowOf(sessionId: String): MutableStateFlow<Boolean> =
        sessionThinkingLives.getOrPut(sessionId) { MutableStateFlow(false) }

    /** 从历史消息推断该会话是否启用过推理输出（loadSession 时恢复）。 */
    fun recordThinkingModeFromHistory(sessionId: String, history: List<HarnessMessage>) {
        thinkingModes[sessionId] = history.any { message ->
            (message as? AssistantText)?.reasoning != null || (message as? ToolCall)?.reasoning != null
        }
    }

    /**
     * 运行中实时观测到 reasoning 输出时即时翻转标记。
     * 若只在 loadSession 时从历史推断，本进程内新对话的第二轮仍读不到，
     * 思考链占位（reasoning_content）会断供。
     */
    fun recordThinkingObserved(sessionId: String) {
        if (thinkingModes[sessionId] != true) thinkingModes[sessionId] = true
    }

    /** 下一轮请求上下文组装所需的思考模式读数。 */
    fun requestThinkingMode(sessionId: String): Boolean = thinkingModes[sessionId] ?: false
}
