package top.tianyan.app.harness.bot

import kotlinx.coroutines.flow.first

import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.harness.bot.memory.BotMemoryManager
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.harness.subagent.SubagentLaneResult
import top.tianyan.app.harness.subagent.SubagentLaneRunner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PersonaBot 生命周期编排：独占会话、模型选择、人格记忆与后台 Lane 委派。
 *
 * 新建会话采用 FULL_ACCESS；已有会话保留执行目标，群聊通过审批面板恢复等待的操作。
 * Bot 生命周期操作另有主控 Bot 身份校验（见 BotManagementPlugin）。
 */
class BotOrchestrator(
    private val botRepository: PersonaBotRepository,
    private val groupRepository: GroupChatRepository,
    private val groupSessionRepository: BotGroupSessionRepository,
    private val sessionRepository: HarnessSessionRepository,
    private val sessionControl: SessionControl,
    private val laneRunner: SubagentLaneRunner,
    private val memoryManager: BotMemoryManager,
    private val logger: AppLogger,
    private val modelManager: BotModelManager,
    private val assignmentRepository: top.tianyan.app.core.database.repository.BotAssignmentRepository? = null,
) {
    private val groupSessionLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun createBot(
        name: String,
        role: String,
        systemPrompt: String,
        modelId: String? = null,
        isMainBot: Boolean = false,
        colorHex: String = "#3D5AFE",
        groupId: String? = null,
        avatarSeed: String? = null,
        modelVariant: String? = null,
        allowModelSelection: Boolean = false,
    ): Bot {
        val cleanName = name.trim().take(40)
        val cleanRole = role.trim().take(40)
        require(cleanName.isNotBlank()) { "Bot 名称不能为空" }
        require(cleanRole.isNotBlank()) { "Bot 职责不能为空" }
        require(systemPrompt.isNotBlank()) { "Bot 人格设定不能为空" }
        val selection = modelManager.validate(modelId, modelVariant)

        val existing = botRepository.listBots()
        val main = isMainBot || existing.none { it.isMainBot }
        val botId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val bot = Bot(
            id = botId,
            name = cleanName,
            role = cleanRole,
            systemPrompt = systemPrompt.trim(),
            avatarSeed = avatarSeed?.takeIf { it.isNotBlank() } ?: cleanName,
            modelId = selection.first,
            modelVariant = selection.second,
            allowModelSelection = allowModelSelection,
            providerId = null,
            sessionId = sessionId,
            isMainBot = main,
            colorHex = colorHex,
            createdAt = now,
            updatedAt = now,
        )

        try {
            sessionRepository.upsert(sessionEntity(bot, now))
            memoryManager.initRepo(botId, cleanName)
            memoryManager.writeMemory(botId, BotMemoryManager.PERSONA, bot.systemPrompt, "feat: 写入初始 Persona")
            botRepository.saveBot(bot)
            if (groupId != null) {
                val order = groupRepository.getMembers(groupId).size
                groupRepository.addMember(groupId, botId, order)
            }
            logger.i("[BotOrchestrator] 已孵化 Bot: $cleanName ($botId), session=$sessionId")
            return bot
        } catch (failure: Throwable) {
            runCatching { botRepository.deleteBot(botId) }
            runCatching { sessionRepository.deleteSession(sessionId) }
            runCatching { memoryManager.deleteMemory(botId) }
            throw failure
        }
    }

    suspend fun updateBot(
        botId: String,
        name: String,
        role: String,
        systemPrompt: String,
        modelId: String?,
        isMainBot: Boolean,
        avatarSeed: String? = null,
        modelVariant: String? = null,
        allowModelSelection: Boolean? = null,
        capabilityServerNames: List<String>? = null,
    ): Bot = modelManager.withBotLock(botId) {
        val current = requireNotNull(botRepository.getBot(botId)) { "Bot 不存在" }
        val selection = modelManager.validate(modelId, modelVariant)
        val cleanName = name.trim().take(40).ifBlank { current.name }
        val cleanRole = role.trim().take(40).ifBlank { current.role }
        val keepMain = if (!isMainBot && current.isMainBot) {
            !botRepository.listBots().any { it.id != botId && it.isMainBot }
        } else {
            isMainBot
        }
        val updated = current.copy(
            name = cleanName,
            role = cleanRole,
            systemPrompt = systemPrompt.trim().ifBlank { current.systemPrompt },
            modelId = selection.first,
            modelVariant = selection.second,
            allowModelSelection = allowModelSelection ?: current.allowModelSelection,
            isMainBot = keepMain,
            avatarSeed = avatarSeed?.takeIf { it.isNotBlank() } ?: current.avatarSeed,
            capabilityServerNames = capabilityServerNames ?: current.capabilityServerNames,
            updatedAt = System.currentTimeMillis(),
        )
        botRepository.saveBot(updated)
        sessionRepository.rename(updated.sessionId, sessionTitle(updated), updated.updatedAt)
        modelManager.syncSessions(updated)
        if (updated.systemPrompt != current.systemPrompt) {
            memoryManager.writeMemory(botId, BotMemoryManager.PERSONA, updated.systemPrompt, "feat: 更新 Persona 设定")
        }
        updated
    }

    suspend fun deleteBot(botId: String) {
        val bot = botRepository.getBot(botId) ?: return
        val adminGroups = groupRepository.countGroupsAsAdmin(botId)
        check(adminGroups == 0) { "该 Bot 仍是 $adminGroups 个群聊的管理员，请先转移管理员" }
        // 借鉴 DS Bot 的删除保护：主控 Bot 不能直接删除，须先经 [setMainBot] 移交主控角色，
        // 避免静默换帅让团队指挥链在用户不知情时变更。
        check(!bot.isMainBot) { "主控 Bot 不能直接删除，请先把主控角色移交给其他 Bot" }
        assignmentRepository?.assignmentsForBot(botId)?.forEach { task ->
            assignmentRepository.cancelAssignment(task.id)
            task.sessionId?.let { sessionControl.deleteSession(it) }
        }
        runCatching { sessionControl.deleteSession(bot.sessionId) }
            .onFailure { logger.w("[BotOrchestrator] 删除 Bot 会话失败: ${it.message}", it) }
        runCatching { groupSessionRepository.listSessionIdsForBot(botId) }.getOrDefault(emptyList())
            .forEach { sessionId ->
                runCatching { sessionControl.deleteSession(sessionId) }
                    .onFailure { logger.w("[BotOrchestrator] 删除群维度会话失败: ${it.message}", it) }
            }
        botRepository.deleteBot(botId)
        memoryManager.deleteMemory(botId)
        logger.i("[BotOrchestrator] 已删除 Bot: ${bot.name}")
    }

    /**
     * 主控角色移交（仅用户可调用；工具侧没有对应参数）。同一时间只保留一个主控：
     * 先撤销现有主控，再提升目标；Bot 会话与人格不受影响。
     */
    suspend fun setMainBot(botId: String): Bot = modelManager.withBotLock(botId) {
        val target = requireNotNull(botRepository.getBot(botId)) { "Bot 不存在" }
        val now = System.currentTimeMillis()
        botRepository.listBots().filter { it.isMainBot && it.id != botId }.forEach { current ->
            botRepository.saveBot(current.copy(isMainBot = false, updatedAt = now))
        }
        val promoted = target.copy(isMainBot = true, updatedAt = now)
        botRepository.saveBot(promoted)
        logger.i("[BotOrchestrator] 主控已移交: ${promoted.name}")
        promoted
    }

    /** 独占会话可能被用户在会话列表里删除；发言前自愈，避免 SESSION_NOT_FOUND。 */
    suspend fun ensureSession(bot: Bot) = modelManager.withBotLock(bot.id) {
        val current = requireNotNull(botRepository.getBot(bot.id)) { "Bot 不存在" }
        ensureSessionRow(current, current.sessionId, sessionTitle(current))
    }

    /**
     * 群维度会话：同一 Bot 在不同群使用独立 Harness 会话，历史、上下文与 Lane 互不污染。
     * 映射缺失时按需创建；同一 (bot, group) 只会创建一次。
     */
    suspend fun ensureGroupSession(bot: Bot, groupId: String): String = modelManager.withBotLock(bot.id) {
        ensureGroupSessionLocked(requireNotNull(botRepository.getBot(bot.id)) { "Bot 不存在" }, groupId)
    }

    private suspend fun ensureGroupSessionLocked(bot: Bot, groupId: String): String =
        groupSessionLocks.computeIfAbsent("${bot.id}:$groupId") { Mutex() }.withLock {
        val existing = groupSessionRepository.sessionIdFor(bot.id, groupId)
        val group = groupRepository.getGroup(groupId)
        val title = groupSessionTitle(bot, group?.name)
        // 群统一审批模式（DS Bot"全员统一沙盒预设"）：群维度会话创建与自愈都对齐该模式，
        // 覆盖成员个人会话的默认权限；null = 沿用完全访问。
        val approvalMode = group?.unifiedApprovalMode?.takeIf { it.isNotBlank() }
            ?: ApprovalMode.FULL_ACCESS.id
        if (existing != null && sessionRepository.findById(existing) != null) {
            ensureSessionRow(bot, existing, title, approvalMode)
            return@withLock existing
        }
        // 修复旧版本留下的悬空映射；重建必须使用新 ID，不能重新匹配旧诊断日志。
        if (existing != null) sessionControl.deleteSession(existing)
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.upsert(sessionEntity(bot, System.currentTimeMillis(), sessionId, title, approvalMode))
        groupSessionRepository.save(bot.id, groupId, sessionId)
        logger.i("[BotOrchestrator] 创建群维度 Bot 会话: ${bot.name} @ $groupId ($sessionId)")
        sessionId
    }

    suspend fun groupSessionId(botId: String, groupId: String): String? =
        groupSessionRepository.sessionIdFor(botId, groupId)

    suspend fun groupIdForSession(sessionId: String): String? =
        groupSessionRepository.groupIdForSession(sessionId)

    /** 解散群聊前移除各 Bot 的群维度会话，避免留下无主会话。 */
    suspend fun deleteGroupSessions(groupId: String) {
        val independent = assignmentRepository?.observeForGroup(groupId)?.first().orEmpty()
            .filter { it.independent }.mapNotNull { it.sessionId }
        (groupSessionRepository.listSessionIdsForGroup(groupId) + independent).distinct().forEach { sessionId ->
            sessionControl.deleteSession(sessionId)
        }
    }

    private suspend fun ensureSessionRow(
        bot: Bot,
        sessionId: String,
        title: String,
        approvalMode: String = ApprovalMode.FULL_ACCESS.id,
    ) {
        val existing = sessionRepository.findById(sessionId)
        if (existing == null) {
            sessionRepository.upsert(
                sessionEntity(bot, System.currentTimeMillis(), sessionId, title, approvalMode),
            )
            logger.w("[BotOrchestrator] Bot 会话缺失，已重建: ${bot.name} ($sessionId)")
            return
        }
        val now = System.currentTimeMillis()
        // Bot 私聊会话保持完全访问（自动执行没有审批 UI）；群维度会话按群统一审批模式对齐，
        // 有统一模式的群把旧会话收敛到该模式，没有的沿用完全访问。
        if (existing.approvalMode != approvalMode) {
            sessionRepository.upsert(
                existing.copy(
                    approvalMode = approvalMode,
                    updatedAt = now,
                ),
            )
            logger.i("[BotOrchestrator] Bot 会话审批模式已对齐: ${bot.name} ($approvalMode)")
        }
        if (existing.modelId != bot.modelId || existing.modelVariant != bot.modelVariant) {
            sessionRepository.setModelSelection(sessionId, bot.modelId, bot.modelVariant, now)
        }
    }

    suspend fun sendMessageToBot(botId: String, text: String): PromptSubmission {
        val bot = requireNotNull(botRepository.getBot(botId)) { "Bot $botId 不存在" }
        ensureSession(bot)
        return sessionControl.submit(bot.sessionId, text)
    }

    suspend fun dispatchSubtask(
        botId: String,
        taskPrompt: String,
        writePaths: List<String> = emptyList(),
        extraContext: String = "",
        laneName: String? = null,
        groupId: String? = null,
    ): SubagentLaneResult {
        val bot = requireNotNull(botRepository.getBot(botId)) { "Bot $botId 未就绪" }
        val sessionId = if (groupId != null) {
            ensureGroupSession(bot, groupId)
        } else {
            ensureSession(bot)
            bot.sessionId
        }
        val memoryContext = memoryManager.assembleContext(botId)
        val prompt = buildString {
            if (memoryContext.isNotBlank()) appendLine(memoryContext).appendLine()
            if (extraContext.isNotBlank()) appendLine(extraContext).appendLine()
            appendLine("【当前指派的专项工作】")
            appendLine(taskPrompt)
        }
        val workspace = sessionRepository.findById(sessionId)?.workspace.orEmpty()
        val resolvedLaneName = laneName
            ?: "subagent:bot-${bot.id.take(8)}:${UUID.randomUUID().toString().take(8)}"
        return laneRunner.run(
            sessionId = sessionId,
            laneName = resolvedLaneName,
            prompt = prompt,
            workspace = workspace,
            modelId = bot.modelId,
            modelVariant = bot.modelVariant,
            writePaths = writePaths,
        )
    }

    suspend fun findBotBySession(sessionId: String): Bot? = botRepository.getBotBySessionId(sessionId)

    private fun sessionTitle(bot: Bot): String = "🤖 ${bot.name}（${bot.role}）"

    private fun groupSessionTitle(bot: Bot, groupName: String?): String =
        "🤖 ${bot.name}（${bot.role}）· ${groupName ?: "群聊"}"

    private fun sessionEntity(
        bot: Bot,
        now: Long,
        sessionId: String = bot.sessionId,
        title: String = sessionTitle(bot),
        approvalMode: String = ApprovalMode.FULL_ACCESS.id,
    ) = HarnessSessionEntity(
        id = sessionId,
        title = title,
        createdAt = now,
        updatedAt = now,
        modelId = bot.modelId,
        modelVariant = bot.modelVariant,
        workspace = "",
        projectType = "",
        approvalMode = approvalMode,
        executionTargetJson = "unbound",
    )
}
