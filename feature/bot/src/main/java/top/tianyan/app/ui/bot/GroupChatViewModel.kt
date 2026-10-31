package top.tianyan.app.ui.bot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupReplyMode
import top.tianyan.app.feature.bot.R
import top.tianyan.app.harness.bot.ActiveBotAssignment
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.bot.diagnostics.BotDiagnosticsExporter
import top.tianyan.app.harness.groupchat.GroupChatDispatcher

data class GroupChatUiState(
    val group: GroupChat? = null,
    val sending: Boolean = false,
    val exporting: Boolean = false,
    val stopping: Boolean = false,
    val deleting: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(
    private val context: Context,
    private val groupRepository: GroupChatRepository,
    private val botRepository: PersonaBotRepository,
    private val dispatcher: GroupChatDispatcher,
    private val exporter: BotDiagnosticsExporter,
    private val assignments: BotAssignmentTracker,
    private val assignmentRuntime: top.tianyan.app.harness.bot.BotAssignmentRuntime,
    private val orchestrator: BotOrchestrator,
    private val scheduleRepository: BotScheduleRepository,
    private val groupSessions: BotGroupSessionRepository,
    private val approvals: AgentApprovalRepository,
    private val sessionControl: SessionControl,
    private val budgets: top.tianyan.app.core.database.repository.TaskBudgetRepository? = null,
    private val memberGroups: top.tianyan.app.core.database.repository.BotMemberGroupRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState())
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val _exportFile = MutableStateFlow<File?>(null)
    val exportFile = _exportFile.asStateFlow()

    private val groupId = MutableStateFlow<String?>(null)
    val teamGroups = memberGroups?.observeGroups()?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        ?: MutableStateFlow(emptyList<top.tianyan.app.core.model.bot.BotMemberGroup>())
    suspend fun referenceTeam(id: String) { groupRepository.attachTeam(requireNotNull(groupId.value), id) }
    init {
        viewModelScope.launch {
            groupId.filterNotNull().flatMapLatest { id -> groupRepository.observeGroups().map { rows -> rows.firstOrNull { it.id == id } } }
                .collect { group -> _uiState.update { it.copy(group = group) } }
        }
    }
    val taskBudgets = groupId.filterNotNull().flatMapLatest { budgets?.observe(it) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun saveBudget(id: String?, tokens: Long) {
        val repo = requireNotNull(budgets) { "预算设置不可用" }
        val group = requireNotNull(groupId.value)
        if (id == null) {
            repo.setDefault(group, tokens)
            _uiState.update { it.copy(group = it.group?.copy(taskBudgetTokens = tokens)) }
        } else repo.setLimit(group, id, tokens)
    }
    val team = groupId.filterNotNull().flatMapLatest { groupRepository.observeTeam(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val governance = groupId.filterNotNull().flatMapLatest { groupRepository.observeGovernance(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), top.tianyan.app.core.model.bot.GroupGovernance())
    val proposals = groupId.filterNotNull().flatMapLatest { groupRepository.observeProposals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<GroupMessage>> = groupId.filterNotNull()
        .flatMapLatest { dispatcher.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val members: StateFlow<List<Bot>> = groupId.filterNotNull()
        .flatMapLatest { dispatcher.observeMembers(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingApprovals: StateFlow<List<AgentApprovalRequestEntity>> = groupId.filterNotNull()
        .flatMapLatest { id ->
            combine(sessionControl.sessionRunStates, assignments.observeForGroup(id)) { _, tasks -> tasks }.flatMapLatest { tasks ->
                val ids = (groupSessions.listSessionIdsForGroup(id) + tasks.filter { it.independent }.mapNotNull { it.sessionId }).distinct()
                if (ids.isEmpty()) flowOf(emptyList())
                else combine(ids.map(approvals::pendingForSession)) { rows -> rows.flatMap { it }.sortedBy { it.createdAt } }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resolveApproval(id: String, approved: Boolean) {
        if (pendingApprovals.value.any { it.id == id }) sessionControl.resolveApproval(id, approved)
    }

    fun answerQuestion(id: String, answers: String) {
        if (pendingApprovals.value.any { it.id == id }) sessionControl.resolveQuestion(id, answers)
    }

    val allBots: StateFlow<List<Bot>> = botRepository.observeBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAssignments: StateFlow<List<ActiveBotAssignment>> = groupId.filterNotNull()
        .flatMapLatest { id -> assignments.active.map { list -> list.filter { it.groupId == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupAssignments: StateFlow<List<BotAssignment>> = groupId.filterNotNull()
        .flatMapLatest { assignments.observeForGroup(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bind(groupId: String) {
        if (this.groupId.value == groupId) return
        this.groupId.value = groupId
        viewModelScope.launch {
            _uiState.update { it.copy(group = groupRepository.getGroup(groupId)) }
        }
    }

    fun send(text: String, attachments: List<GroupAttachment> = emptyList()) {
        val groupId = groupId.value ?: return
        if ((text.isBlank() && attachments.isEmpty()) || _uiState.value.sending) return
        viewModelScope.launch {
            _uiState.update { it.copy(sending = true) }
            runCatching { dispatcher.onUserSend(groupId, text, attachments) }
                .onFailure { _notices.tryEmit("发送失败：${it.message}") }
            _uiState.update { it.copy(sending = false) }
        }
    }

    fun updateGroup(
        name: String,
        description: String,
        background: String,
        replyMode: GroupReplyMode,
        unifiedApprovalMode: String? = currentUnifiedApprovalMode(),
    ) {
        val groupId = groupId.value ?: return
        val current = _uiState.value.group ?: return
        viewModelScope.launch {
            runCatching {
                val updated = current.copy(
                    name = name.trim().take(40).ifBlank { current.name },
                    description = description.trim().take(200),
                    background = background,
                    replyMode = replyMode,
                    unifiedApprovalMode = unifiedApprovalMode,
                )
                groupRepository.saveGroup(updated)
                updated
            }.onSuccess {
                _uiState.update { state -> state.copy(group = it) }
                _notices.tryEmit(context.getString(R.string.fbot_group_updated))
            }.onFailure {
                _notices.tryEmit(context.getString(R.string.fbot_error, it.message.orEmpty()))
            }
        }
    }

    private fun currentUnifiedApprovalMode(): String? =
        _uiState.value.group?.unifiedApprovalMode

    /** 群管理员移交（仅用户操作；新管理员须为本群成员，团队组不可单独移交）。 */
    fun transferAdmin(newAdminBotId: String) {
        val groupId = groupId.value ?: return
        viewModelScope.launch {
            runCatching { groupRepository.transferAdmin(groupId, newAdminBotId) }
                .onSuccess { _uiState.update { state -> state.copy(group = groupRepository.getGroup(groupId)) } }
                .onFailure { _notices.tryEmit("移交失败：${it.message}") }
        }
    }

    /** 解散群聊：停止运行、删除群维度会话与定时任务，再删除群记录。 */
    fun deleteGroup(onDeleted: () -> Unit) {
        val groupId = groupId.value ?: return
        if (_uiState.value.deleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true) }
            runCatching {
                assignmentRuntime.cancelGroup(groupId)
                dispatcher.cancelGroup(groupId)
                assignments.cancelGroup(groupId)
                orchestrator.deleteGroupSessions(groupId)
                botRepository.listBots().forEach { bot ->
                    scheduleRepository.observeForBot(bot.id).first()
                        .filter { it.groupId == groupId }
                        .forEach { scheduleRepository.deleteSchedule(it.id) }
                }
                groupRepository.deleteGroup(groupId)
            }.onSuccess {
                onDeleted()
            }.onFailure {
                _uiState.update { state -> state.copy(deleting = false) }
                _notices.tryEmit(context.getString(R.string.fbot_error, it.message.orEmpty()))
            }
        }
    }

    fun clearMessages() {
        val groupId = groupId.value ?: return
        viewModelScope.launch {
            runCatching {
                assignmentRuntime.cancelGroup(groupId)
                dispatcher.cancelGroup(groupId)
                assignments.cancelGroup(groupId)
                orchestrator.deleteGroupSessions(groupId)
                groupRepository.clearMessages(groupId)
                _exportFile.value = null
            }
                .onFailure { _notices.tryEmit("清空失败：${it.message}") }
        }
    }

    suspend fun continueAssignment(id: String, instruction: String, requestId: String) {
        assignmentRuntime.continueTask(id, requireNotNull(groupId.value), instruction, requestId)
    }

    fun exportDiagnostics() {
        val groupId = groupId.value ?: return
        if (_uiState.value.exporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            runCatching {
                val dir = File(context.cacheDir, "bot-diagnostics").apply { mkdirs() }
                val rawName = _uiState.value.group?.name ?: groupId.take(8)
                val safeName = rawName.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                val target = File(dir, "group-$safeName-${System.currentTimeMillis()}.md")
                exporter.exportGroup(groupId, target)
                target
            }.onSuccess {
                _exportFile.value = it
                _notices.tryEmit(context.getString(R.string.fbot_diagnostics_exported))
            }.onFailure {
                _notices.tryEmit(context.getString(R.string.fbot_diagnostics_failed, it.message))
            }
            _uiState.update { it.copy(exporting = false) }
        }
    }

    fun consumeExportFile() {
        _exportFile.value = null
    }

    fun stop() {
        val groupId = groupId.value ?: return
        if (_uiState.value.stopping) return
        viewModelScope.launch {
            _uiState.update { it.copy(stopping = true) }
            val background = activeAssignments.value
            runCatching { assignmentRuntime.cancelGroup(groupId)
                dispatcher.cancelGroup(groupId) }
                .onFailure { _notices.tryEmit(context.getString(R.string.fbot_stop_failed, it.message)) }
            assignments.cancelGroup(groupId)
            if (background.isNotEmpty()) {
                runCatching {
                    dispatcher.postSystemNotification(
                        groupId,
                        context.getString(
                            R.string.fbot_assignment_stopped,
                            background.joinToString("、") { it.botName },
                        ),
                    )
                }
            }
            _uiState.update { it.copy(stopping = false) }
        }
    }

    fun cancelAssignment(taskId: String) {
        viewModelScope.launch {
            runCatching { assignments.cancel(taskId) }
                .onSuccess { _notices.tryEmit(context.getString(R.string.fbot_work_cancelled)) }
                .onFailure {
                    _notices.tryEmit(context.getString(R.string.fbot_work_cancel_failed, it.message.orEmpty()))
                }
        }
    }

    private companion object {
        const val USER_ID = "user_me"
        const val USER_NAME = "我"
    }

    fun saveMembers(selectedIds: Set<String>) {
        val groupId = groupId.value ?: return
        viewModelScope.launch {
            runCatching {
                val currentMembers = groupRepository.getMembers(groupId)
                val current = currentMembers.map { it.id }.toSet()
                (selectedIds - current).forEachIndexed { index, id ->
                    groupRepository.addMember(groupId, id, currentMembers.size + index)
                }
                (current - selectedIds).forEach { id -> groupRepository.removeMember(groupId, id) }
            }.onSuccess {
                _notices.tryEmit("群成员已更新")
            }.onFailure {
                _notices.tryEmit("更新成员失败：${it.message}")
            }
        }
    }
}
