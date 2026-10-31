package top.tianyan.app.ui.bot

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.database.repository.BotMemberGroupRepository
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotMemberGroup
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.MarketBotItem
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.bot.archive.BotPackageService
import top.tianyan.app.harness.bot.market.BotMarketRepository
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup

data class BotListUiState(
    val busy: Boolean = false,
    val installingId: String? = null,
)

class BotListViewModel(
    private val context: Context,
    private val botRepository: PersonaBotRepository,
    private val groupRepository: GroupChatRepository,
    private val memberGroupRepository: BotMemberGroupRepository,
    private val scheduleRepository: BotScheduleRepository,
    private val orchestrator: BotOrchestrator,
    private val marketRepository: BotMarketRepository,
    private val packageService: BotPackageService,
    private val models: top.tianyan.app.core.database.AiModelRepository,
    private val wakeup: BotScheduleWakeup? = null,
    private val teamImporter: top.tianyan.app.harness.bot.market.GroupTeamImporter? = null,
) : ViewModel() {
    val modelProfiles: StateFlow<List<top.tianyan.app.core.database.AiModelEntity>?> = models.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bots: StateFlow<List<Bot>> = botRepository.observeBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<GroupChat>> = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memberGroups: StateFlow<List<BotMemberGroup>> = memberGroupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(BotListUiState())
    val uiState: StateFlow<BotListUiState> = _uiState.asStateFlow()

    private val _marketItems = MutableStateFlow<List<MarketBotItem>>(emptyList())
    val marketItems = _marketItems.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices = _notices.asSharedFlow()

    init {
        refreshMarket()
    }

    fun refreshMarket() {
        viewModelScope.launch {
            runCatching { marketRepository.catalog() }
                .onSuccess { _marketItems.value = it }
                .onFailure { emit("市场加载失败：${it.message}") }
        }
    }

    fun createBot(name: String, role: String, persona: String, groupId: String?, avatarSeed: String?) {
        if (name.isBlank() || role.isBlank() || persona.isBlank()) {
            emit("请填写名称、职责与人格设定")
            return
        }
        launchBusy {
            val bot = orchestrator.createBot(
                name = name,
                role = role,
                systemPrompt = persona,
                groupId = groupId,
                avatarSeed = avatarSeed,
            )
            "已创建 ${bot.name}"
        }
    }

    suspend fun importTeam(templateId: String, name: String): String =
        requireNotNull(teamImporter).importDefinition(templateId, name).id

    fun createGroup(name: String, description: String, adminBotId: String, memberIds: Set<String>, teamGroupId: String? = null) {
        if (name.isBlank() || adminBotId.isBlank()) {
            emit("请填写群聊名称并选择管理员")
            return
        }
        launchBusy {
            val allBots = botRepository.listBots()
            val ordered = listOf(adminBotId) + allBots.map { it.id }
                .filter { it != adminBotId && it in memberIds }
            val groupId = UUID.randomUUID().toString()
            val group = GroupChat(
                    id = groupId,
                    name = name.trim().take(40),
                    adminBotId = adminBotId,
                    description = description.trim().take(200),
                    createdAt = System.currentTimeMillis(),
                )
            if (teamGroupId != null) groupRepository.createReferencedGroup(group, teamGroupId)
            else {
                groupRepository.saveGroup(group)
                ordered.forEachIndexed { index, botId -> groupRepository.addMember(groupId, botId, index) }
            }
            "已创建群聊「${name.trim()}」"
        }
    }

    suspend fun teamImpact(id: String) = memberGroupRepository.publicationImpact(id)
    suspend fun teamVersions(id: String) = memberGroupRepository.observeVersions(id).first()
    suspend fun publishTeam(id: String, revision: Int, selected: Set<String>) = memberGroupRepository.publish(id, revision, selected)
    suspend fun upgradeTeams(id: String, version: Int, selected: Set<String>) = memberGroupRepository.upgrade(id, version, selected)

    suspend fun saveTeamGroup(group: BotMemberGroup) {
        require(group.name.isNotBlank()) { "请填写团队名称" }
        requireNotNull(group.definition).validate(group.memberIds)
        memberGroupRepository.saveGroup(group.copy(name = group.name.trim().take(40)))
    }

    fun saveMemberGroup(group: BotMemberGroup) {
        if (group.name.isBlank()) {
            emit("请填写分组名称")
            return
        }
        launchBusy {
            val trimmed = group.copy(name = group.name.trim().take(40))
            memberGroupRepository.saveGroup(trimmed)
            "已保存分组「${trimmed.name}」"
        }
    }

    fun deleteMemberGroup(group: BotMemberGroup) {
        launchBusy {
            memberGroupRepository.deleteGroup(group.id)
            "已删除分组「${group.name}」"
        }
    }

    fun deleteGroup(group: GroupChat) {
        viewModelScope.launch {
            runCatching {
                orchestrator.deleteGroupSessions(group.id)
                groupRepository.deleteGroup(group.id)
                botRepository.listBots().forEach { bot ->
                    scheduleRepository.observeForBot(bot.id).first()
                        .filter { it.groupId == group.id }
                        .forEach { scheduleRepository.deleteSchedule(it.id) }
                }
                wakeup?.request()
            }.onSuccess { emit("已删除群聊「${group.name}」") }
                .onFailure { emit("操作失败：${it.message}") }
        }
    }

    fun installMarketBot(item: MarketBotItem) {
        if (_uiState.value.installingId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(installingId = item.id)
            runCatching { marketRepository.install(item.id) }
                .onSuccess { emit("已安装 ${it.name}") }
                .onFailure { emit("操作失败：${it.message}") }
            _uiState.value = _uiState.value.copy(installingId = null)
        }
    }

    fun importPackage(uri: Uri, groupId: String? = null) {
        launchBusy {
            val target = File(context.cacheDir, "bot-import-${System.currentTimeMillis()}.zip")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().buffered().use { input.copyTo(it) }
                    } ?: error("无法读取所选文件")
                }
                val bot = packageService.importBot(target, groupId)
                "已导入 ${bot.name}"
            } finally {
                target.delete()
            }
        }
    }

    private fun launchBusy(block: suspend () -> String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            runCatching { block() }
                .onSuccess { emit(it) }
                .onFailure { emit("操作失败：${it.message}") }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    private fun emit(message: String) {
        _notices.tryEmit(message)
    }
}
