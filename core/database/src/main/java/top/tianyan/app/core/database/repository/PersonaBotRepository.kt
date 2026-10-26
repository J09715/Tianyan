package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.BotEntity
import top.tianyan.app.core.database.BotGroupSessionDao
import top.tianyan.app.core.database.BotScheduleDeliveryDao
import top.tianyan.app.core.model.bot.BotScheduleDelivery
import top.tianyan.app.core.database.BotScheduleDao
import top.tianyan.app.core.database.BotScheduleEntity
import top.tianyan.app.core.database.GroupChatDao
import top.tianyan.app.core.database.GroupChatEntity
import top.tianyan.app.core.database.GroupMemberEntity
import top.tianyan.app.core.database.GroupMessageEntity
import top.tianyan.app.core.database.GroupSeenEntity
import top.tianyan.app.core.database.PersonaBotDao
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotSchedule
import top.tianyan.app.core.model.bot.BotSessionGroupRef
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupReaction
import top.tianyan.app.core.model.bot.GroupReplyMode
import top.tianyan.app.core.model.bot.GroupRound
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.core.model.bot.GroupToolStep

interface PersonaBotRepository {
    fun observeBots(): Flow<List<Bot>>
    fun observeBot(id: String): Flow<Bot?>
    fun observeBotSessionIds(): Flow<Set<String>>
    fun observeGroupSessionRefs(): Flow<Map<String, BotSessionGroupRef>>
    suspend fun getBot(id: String): Bot?
    suspend fun getBotBySessionId(sessionId: String): Bot?
    suspend fun getMainBot(): Bot?
    suspend fun listBots(): List<Bot>
    suspend fun findBotByName(name: String): Bot?
    suspend fun saveBot(bot: Bot)
    suspend fun deleteBot(id: String)
}

interface GroupChatRepository : GroupDeliveryRepository, GroupRoundRepository, GroupGovernanceRepository {
    suspend fun getTeam(groupId: String): top.tianyan.app.core.model.bot.GroupTeam {
        val group = requireNotNull(getGroup(groupId)) { "群聊不存在" }
        return top.tianyan.app.core.model.bot.GroupTeam(group.adminBotId, getMembers(groupId).map { it.id }.toSet())
    }
    fun observeTeam(groupId: String): Flow<top.tianyan.app.core.model.bot.GroupTeam> =
        observeMembers(groupId).map { getTeam(groupId) }
    suspend fun saveTeam(groupId: String, supervisors: Map<String, String>) { error("团队结构保存不可用") }
    suspend fun saveIndependentTeam(team: top.tianyan.app.core.model.bot.BotMemberGroup) { error("团队组保存不可用") }
    suspend fun attachTeam(groupId: String, teamId: String) { error("团队组引用不可用") }
    suspend fun createReferencedGroup(group: GroupChat, teamId: String) { error("团队建群不可用") }
    suspend fun installTeamTemplate(group: GroupChat, members: List<String>, supervisors: Map<String, String>,
        policy: top.tianyan.app.core.model.bot.GroupGovernance, createNew: Boolean) { error("团队模板导入不可用") }
    fun observeGroups(): Flow<List<GroupChat>>
    fun observeGroupsForBot(botId: String): Flow<List<GroupChat>>
    suspend fun getGroup(id: String): GroupChat?
    suspend fun countGroupsAsAdmin(botId: String): Int
    suspend fun saveGroup(group: GroupChat)
    /** 群管理员移交：新管理员必须是本群成员；团队组的管理员由团队定义，不能单独移交。 */
    suspend fun transferAdmin(groupId: String, newAdminBotId: String)
    suspend fun deleteGroup(id: String)
    fun observeMembers(groupId: String): Flow<List<Bot>>
    suspend fun getMembers(groupId: String): List<Bot>
    suspend fun addMember(groupId: String, botId: String, order: Int)
    suspend fun removeMember(groupId: String, botId: String)
    fun observeMessages(groupId: String): Flow<List<GroupMessage>>
    suspend fun postMessage(message: GroupMessage)
    suspend fun stageUserMessageWithRound(message: GroupMessage, round: GroupRound)
    suspend fun deleteMessage(id: String)
    suspend fun latestSeq(groupId: String): Long
    suspend fun getSeenSeq(groupId: String, botId: String): Long
    suspend fun setSeenSeq(groupId: String, botId: String, seq: Long)
    suspend fun updateStreamingMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        steps: List<GroupToolStep> = emptyList(),
        reasoning: String? = null,
    )
    suspend fun toggleReaction(messageId: String, emoji: String, reactorId: String, reactorName: String)
    suspend fun clearMessages(groupId: String)
}

interface BotScheduleRepository {
    fun observeForBot(botId: String): Flow<List<BotSchedule>>
    suspend fun listDue(now: Long): List<BotSchedule>
    suspend fun nextSchedule(): BotSchedule?
    suspend fun getSchedule(id: String): BotSchedule?
    suspend fun saveSchedule(schedule: BotSchedule)
    suspend fun deleteSchedule(id: String)
    suspend fun deleteForBot(botId: String)
    suspend fun markRun(id: String, lastRunAt: Long, nextRunAt: Long)
    suspend fun setScheduleEnabled(id: String, enabled: Boolean)
    suspend fun stageDelivery(schedule: BotSchedule, now: Long, next: Long)
    suspend fun pendingDeliveries(limit: Int): List<BotScheduleDelivery>
    suspend fun claimDelivery(id: String): Boolean
    suspend fun finishDelivery(id: String, status: String, error: String? = null)
    suspend fun recoverInterruptedDeliveries()
    fun observeDeliveries(botId: String): Flow<List<BotScheduleDelivery>>
    suspend fun nextWakeAt(): Long?
}

class RoomPersonaBotRepository(
    private val dao: PersonaBotDao,
    private val groupSessionDao: BotGroupSessionDao? = null,
) : PersonaBotRepository {
    override fun observeBots(): Flow<List<Bot>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeBot(id: String): Flow<Bot?> = observeBots().map { bots -> bots.firstOrNull { it.id == id } }

    override fun observeBotSessionIds(): Flow<Set<String>> =
        dao.observeSessionIds().map { ids -> ids.filter { it.isNotBlank() }.toSet() }

    override fun observeGroupSessionRefs(): Flow<Map<String, BotSessionGroupRef>> =
        dao.observeGroupSessionRefs().map { rows ->
            rows.associateBy(
                keySelector = { it.sessionId },
                valueTransform = { BotSessionGroupRef(it.sessionId, it.groupId, it.groupName) },
            )
        }

    override suspend fun getBot(id: String): Bot? = dao.findById(id)?.toModel()

    override suspend fun getBotBySessionId(sessionId: String): Bot? {
        dao.findBySessionId(sessionId)?.let { return it.toModel() }
        // 群维度会话不在 persona_bots.sessionId 上，需要经映射反查 Bot。
        val botId = groupSessionDao?.findBotIdBySession(sessionId) ?: return null
        return dao.findById(botId)?.toModel()
    }

    override suspend fun getMainBot(): Bot? = dao.findMainBot()?.toModel()

    override suspend fun listBots(): List<Bot> = dao.listAll().map { it.toModel() }

    override suspend fun findBotByName(name: String): Bot? = dao.findByName(name)?.toModel()

    override suspend fun saveBot(bot: Bot) = dao.upsertWithMain(bot.toEntity())

    override suspend fun deleteBot(id: String) = dao.deleteById(id)
}

class RoomGroupChatRepository(
    private val dao: GroupChatDao,
    private val json: Json,
) : GroupChatRepository, GroupDeliveryRepository by RoomGroupDeliveryRepository(dao, json),
    GroupRoundRepository by RoomGroupRoundRepository(dao, json),
    GroupGovernanceRepository by RoomGroupGovernanceRepository(dao) {
    override suspend fun installTeamTemplate(group: GroupChat, members: List<String>, supervisors: Map<String, String>,
        policy: top.tianyan.app.core.model.bot.GroupGovernance, createNew: Boolean) = dao.installTeamTemplate(group.toEntity(), members, supervisors, policy, createNew)
    override suspend fun saveIndependentTeam(team: top.tianyan.app.core.model.bot.BotMemberGroup) {
        val definition = requireNotNull(team.definition) { "团队组缺少组织定义" }
        definition.validate(team.memberIds)
        dao.installIndependentDefinition(top.tianyan.app.core.database.BotMemberGroupEntity(team.id, team.name, team.createdAt,
            json.encodeToString(definition)), team.memberIds.mapIndexed { index, id ->
                top.tianyan.app.core.database.BotMemberGroupMemberEntity(team.id, id, index, System.currentTimeMillis())
            })
    }
    override suspend fun attachTeam(groupId: String, teamId: String) = dao.syncDefinitionToChat(groupId, teamId)
    override suspend fun createReferencedGroup(group: GroupChat, teamId: String) = dao.createReferencedGroup(group.toEntity(), teamId)
    private val reactionMutex = Mutex()
    override suspend fun getTeam(groupId: String): top.tianyan.app.core.model.bot.GroupTeam =
        dao.effectiveTeam(groupId)
    override fun observeTeam(groupId: String): Flow<top.tianyan.app.core.model.bot.GroupTeam> =
        kotlinx.coroutines.flow.combine(dao.observeTeamMembers(groupId), dao.observeLinkedDefinition(groupId)) { _, _ ->
            if (dao.teamAdmin(groupId) == null) top.tianyan.app.core.model.bot.GroupTeam("", emptySet()) else dao.effectiveTeam(groupId)
        }
    override suspend fun saveTeam(groupId: String, supervisors: Map<String, String>) = dao.saveTeam(groupId, supervisors)

    override fun observeGroups(): Flow<List<GroupChat>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeGroupsForBot(botId: String): Flow<List<GroupChat>> =
        dao.observeForBot(botId).map { rows -> rows.map { it.toModel() } }

    override suspend fun getGroup(id: String): GroupChat? = dao.findById(id)?.toModel()

    override suspend fun countGroupsAsAdmin(botId: String): Int = dao.countAsAdmin(botId)

    override suspend fun saveGroup(group: GroupChat) = dao.saveChatConfiguration(group.toEntity())

    override suspend fun transferAdmin(groupId: String, newAdminBotId: String) = dao.transferAdmin(groupId, newAdminBotId)

    override suspend fun deleteGroup(id: String) = dao.deleteById(id)

    override fun observeMembers(groupId: String): Flow<List<Bot>> =
        dao.observeMembers(groupId).map { rows -> rows.map { it.toModel() } }

    override suspend fun getMembers(groupId: String): List<Bot> = dao.getMembers(groupId).map { it.toModel() }

    override suspend fun addMember(groupId: String, botId: String, order: Int) {
        dao.addMember(GroupMemberEntity(groupId, botId, order, System.currentTimeMillis()))
    }

    override suspend fun removeMember(groupId: String, botId: String) = dao.removeMember(groupId, botId)

    override fun observeMessages(groupId: String): Flow<List<GroupMessage>> =
        dao.observeMessages(groupId).map { rows -> rows.map { it.toModel(json) } }

    override suspend fun postMessage(message: GroupMessage) = dao.postMessageWithSeq(message.toEntity(json))

    override suspend fun stageUserMessageWithRound(message: GroupMessage, round: GroupRound) =
        dao.stageUserMessageWithRound(message.toEntity(json), round.toEntity(json))

    override suspend fun deleteMessage(id: String) = dao.deleteMessage(id)

    override suspend fun latestSeq(groupId: String): Long = dao.latestSeq(groupId)

    override suspend fun getSeenSeq(groupId: String, botId: String): Long =
        dao.lastSeenSeq(groupId, botId) ?: 0

    override suspend fun setSeenSeq(groupId: String, botId: String, seq: Long) =
        dao.upsertSeen(GroupSeenEntity(groupId, botId, seq, System.currentTimeMillis()))

    override suspend fun updateStreamingMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        steps: List<GroupToolStep>,
        reasoning: String?,
    ) = dao.updateMessageContent(
        id = id,
        content = content,
        isStreaming = isStreaming,
        stepsJson = json.encodeToString(ListSerializer(GroupToolStep.serializer()), steps),
        reasoning = reasoning,
    )

    override suspend fun toggleReaction(
        messageId: String,
        emoji: String,
        reactorId: String,
        reactorName: String,
    ) {
        reactionMutex.withLock {
            val entity = dao.findMessage(messageId) ?: return@withLock
            val reactions = json.decodeReactions(entity.reactionsJson).toMutableList()
            val index = reactions.indexOfFirst { it.emoji == emoji && it.reactorId == reactorId }
            if (index >= 0) reactions.removeAt(index) else reactions += GroupReaction(emoji, reactorId, reactorName)
            dao.updateMessageReactions(
                messageId,
                json.encodeToString(ListSerializer(GroupReaction.serializer()), reactions),
            )
        }
    }

    override suspend fun clearMessages(groupId: String) = dao.clearMessages(groupId)
}

private fun Json.decodeAttachments(raw: String): List<GroupAttachment> = runCatching {
    decodeFromString(ListSerializer(GroupAttachment.serializer()), raw)
}.getOrDefault(emptyList())

private fun Json.decodeReactions(raw: String): List<GroupReaction> = runCatching {
    decodeFromString(ListSerializer(GroupReaction.serializer()), raw)
}.getOrDefault(emptyList())

private fun Json.decodeSteps(raw: String): List<GroupToolStep> = runCatching {
    decodeFromString(ListSerializer(GroupToolStep.serializer()), raw)
}.getOrDefault(emptyList())

class RoomBotScheduleRepository(private val dao: BotScheduleDao, private val deliveries: BotScheduleDeliveryDao) : BotScheduleRepository {
    override fun observeForBot(botId: String): Flow<List<BotSchedule>> =
        dao.observeForBot(botId).map { rows -> rows.map { it.toModel() } }

    override suspend fun listDue(now: Long): List<BotSchedule> = dao.listDue(now).map { it.toModel() }

    override suspend fun nextSchedule(): BotSchedule? = dao.findNext()?.toModel()

    override suspend fun getSchedule(id: String): BotSchedule? = dao.findById(id)?.toModel()

    override suspend fun saveSchedule(schedule: BotSchedule) = dao.upsert(schedule.toEntity())

    override suspend fun deleteSchedule(id: String) = dao.deleteById(id)

    override suspend fun deleteForBot(botId: String) = dao.deleteForBot(botId)

    override suspend fun markRun(id: String, lastRunAt: Long, nextRunAt: Long) = dao.markRun(id, lastRunAt, nextRunAt)
    override suspend fun setScheduleEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
    override suspend fun stageDelivery(schedule: BotSchedule, now: Long, next: Long) =
        deliveries.stage(schedule.id, schedule.nextRunAt, now, next)
    override suspend fun pendingDeliveries(limit: Int) = deliveries.pending(limit).map { it.toModel() }
    override suspend fun claimDelivery(id: String) = deliveries.claim(id) == 1
    override suspend fun finishDelivery(id: String, status: String, error: String?) = deliveries.finish(id, status, error)
    override suspend fun recoverInterruptedDeliveries() = deliveries.recoverInterrupted()
    override fun observeDeliveries(botId: String) = deliveries.observeForBot(botId).map { rows -> rows.map { it.toModel() } }
    override suspend fun nextWakeAt(): Long? =
        listOfNotNull(dao.findNext()?.nextRunAt?.takeIf { it != Long.MAX_VALUE }, deliveries.nextPendingAt()).minOrNull()
}

private fun BotEntity.toModel() = Bot(
    id = id,
    name = name,
    role = role,
    systemPrompt = systemPrompt,
    avatarSeed = avatarSeed,
    modelId = modelId,
    modelVariant = modelVariant,
    allowModelSelection = allowModelSelection,
    providerId = providerId,
    sessionId = sessionId,
    isMainBot = isMainBot,
    colorHex = colorHex,
    createdAt = createdAt,
    updatedAt = updatedAt,
    capabilityServerNames = decodeCapabilityServers(capabilityServerNames),
)

private fun Bot.toEntity() = BotEntity(
    id = id,
    name = name,
    role = role,
    systemPrompt = systemPrompt,
    avatarSeed = avatarSeed,
    modelId = modelId,
    modelVariant = modelVariant,
    allowModelSelection = allowModelSelection,
    providerId = providerId,
    sessionId = sessionId,
    isMainBot = isMainBot,
    colorHex = colorHex,
    createdAt = createdAt,
    updatedAt = updatedAt,
    capabilityServerNames = encodeCapabilityServers(capabilityServerNames),
)

internal fun GroupMessageEntity.toModel(json: Json) = GroupMessage(
    id = id,
    groupId = groupId,
    senderId = senderId,
    senderName = senderName,
    senderType = runCatching { GroupSenderType.valueOf(senderType) }.getOrDefault(GroupSenderType.SYSTEM),
    content = content,
    timestamp = timestamp,
    isStreaming = isStreaming,
    delegatedTaskId = delegatedTaskId,
    attachments = json.decodeAttachments(attachmentsJson),
    reactions = json.decodeReactions(reactionsJson),
    steps = json.decodeSteps(stepsJson),
    reasoning = reasoning,
    seq = seq,
)

internal fun GroupMessage.toEntity(json: Json) = GroupMessageEntity(
    id = id,
    groupId = groupId,
    senderId = senderId,
    senderName = senderName,
    senderType = senderType.name,
    content = content,
    timestamp = timestamp,
    isStreaming = isStreaming,
    delegatedTaskId = delegatedTaskId,
    attachmentsJson = json.encodeToString(ListSerializer(GroupAttachment.serializer()), attachments),
    reactionsJson = json.encodeToString(ListSerializer(GroupReaction.serializer()), reactions),
    stepsJson = json.encodeToString(ListSerializer(GroupToolStep.serializer()), steps),
    reasoning = reasoning,
    seq = seq,
)

private fun BotScheduleEntity.toModel() = BotSchedule(
    id = id,
    botId = botId,
    groupId = groupId,
    cron = cron,
    instruction = instruction,
    enabled = enabled,
    nextRunAt = nextRunAt,
    lastRunAt = lastRunAt,
    createdAt = createdAt,
)

private fun BotSchedule.toEntity() = BotScheduleEntity(
    id = id,
    botId = botId,
    groupId = groupId,
    cron = cron,
    instruction = instruction,
    enabled = enabled,
    nextRunAt = nextRunAt,
    lastRunAt = lastRunAt,
    createdAt = createdAt,
)
