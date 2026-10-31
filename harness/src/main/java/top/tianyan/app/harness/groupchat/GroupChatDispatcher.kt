package top.tianyan.app.harness.groupchat

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.core.model.bot.GroupDelivery
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupReplyMode
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.harness.MentionExtractor
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

/** 一轮群聊成员回复的结果；主控委派据此判断成员是否真正完成。 */
data class GroupTurnOutcome(
    val text: String,
    val failed: Boolean,
    val uncertain: Boolean = false,
    val passed: Boolean = false,
    val messageId: String? = null,
    /** 成员已开始干活但本轮先让出，最终结果稍后作为迟到回复发出。 */
    val working: Boolean = false,
    val blockedReason: String? = null,
    val nativeTaskId: String? = null,
)

/**
 * 群聊消息分流：精确 @ 路由优先，否则交给 Admin Bot；Bot 独占会话的助手文本
 * 流式回写到群消息表。不在群内做 Bot 间自动广播，避免两个 Bot 互相客套死循环。
 */
class GroupChatDispatcher(
    private val groupRepository: GroupChatRepository,
    private val botRepository: PersonaBotRepository,
    private val orchestrator: BotOrchestrator,
    private val sessionControl: SessionControl,
    private val logger: AppLogger,
    private val attachmentPreparer: BotAttachmentPreparer? = null,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure ->
                logger.e("[GroupChatDispatcher] 未捕获的协程异常", failure)
            },
    ),
    private val freshness: GroupReplyFreshness? = null,
    taskRepository: top.tianyan.app.core.database.task.AgentTaskRepository? = null,
    runtimeRepository: top.tianyan.app.core.database.HarnessRuntimeRepository? = null,
    private val contextScope: GroupContextScope? = null,
) {
    private val replies = GroupReplyQueue(scope)

    private val liveReplies = MutableStateFlow<Map<String, GroupReplyProgress>>(emptyMap())
    private val taskTracker = if (taskRepository != null && runtimeRepository != null)
        GroupReplyTaskTracker(taskRepository, runtimeRepository, sessionControl) else null
    private val responder = GroupBotResponder(
        groupRepository, orchestrator::ensureGroupSession, sessionControl, logger, liveReplies, freshness, scope,
        taskTracker = taskTracker, contextScope = contextScope,
    )
    private val roundCoordinator = GroupRoundCoordinator(
        groups = groupRepository,
        queue = replies,
        responder = { group, bot, trigger, history, members, imageUrls, lateReplyCallerId, onSubmitted, onLateReply ->
            responder.respond(
                group = group,
                bot = bot,
                trigger = trigger,
                history = history,
                members = members,
                imageUrls = imageUrls,
                admission = null,
                silentPass = true,
                promptHistory = history,
                lateReplyCallerId = lateReplyCallerId,
                onSubmitted = onSubmitted,
                onLateReply = onLateReply,
            )
        },
        scope = scope,
        logger = logger,
        delegatedTargets = { groupId, nativeId -> contextScope?.delegatedTargets(groupId, nativeId).orEmpty() },
    )
    private val userAdmission = GroupRoundAdmission(
        groupRepository, roundCoordinator,
        prepare = { requireNotNull(attachmentPreparer) { "附件读取服务尚未就绪" }.prepare(it) },
        notify = { id, text -> postSystemNotification(id, text) },
    )
    private val deliveryPump = GroupDeliveryPump(groupRepository, replies, scope, logger, ::deliver)

    private val recoveryLock = Mutex()
    private var recovered = false

    suspend fun startDeliveryProcessing() = recoveryLock.withLock {
        deliveryPump.start()
        if (recovered) return@withLock
        recovered = true
        try {
            roundCoordinator.recover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupChatDispatcher] 轮次恢复失败: ${failure.message}")
        }
        responder.recoverLateReplies { reply, text ->
            roundCoordinator.lateReplyRecovered(
                reply.groupId, reply.workerId, reply.workerName, reply.callerBotId, text,
                contextScope?.workForNativeTask(reply.taskId),
            )
        }
    }

    private suspend fun deliver(delivery: GroupDelivery): GroupTurnOutcome {
        val source = groupRepository.deliverySource(delivery.sourceMessageId)
        val group = groupRepository.getGroup(delivery.groupId)
        val bot = botRepository.getBot(delivery.botId)
        val members = groupRepository.getMembers(delivery.groupId)
        if (source == null || group == null || bot == null || members.none { it.id == bot.id }) {
            groupRepository.finishDelivery(delivery.id,
                GroupDelivery.REJECTED, "来源、群聊或成员已不可用，未执行")
            return GroupTurnOutcome("来源或成员已不可用", true)
        }
        val prepared = if (source.attachments.isEmpty()) PreparedGroupAttachments() else
            requireNotNull(attachmentPreparer) { "附件读取服务尚未就绪" }.prepare(source.attachments)
        val trigger = buildString {
            appendLine(source.content.ifBlank { "（用户发送了附件，请查看并处理）" })
            if (prepared.description.isNotBlank()) appendLine("【附件】\n${prepared.description}")
        }
        // Read after acquiring the recipient lock so queued work sees recently committed peer replies.
        val history = formatGroupHistory(group, groupRepository.observeMessages(group.id).first())
        return responder.respond(group, bot, trigger, history, members, prepared.imageUrls, null,
            deliveryId = delivery.id, workReceiptId = source.delegatedTaskId)
    }

    fun observeMessages(groupId: String): Flow<List<GroupMessage>> =
        combine(groupRepository.observeMessages(groupId), liveReplies) { messages, live ->
            messages.map { message ->
                val progress = live[message.id]
                if (!message.isStreaming || progress == null) message else message.copy(
                    contentText = progress.contentText, reasoning = progress.reasoning, steps = progress.steps,
                )
            }
        }

    fun observeMembers(groupId: String): Flow<List<Bot>> = groupRepository.observeMembers(groupId)

    suspend fun onUserSend(
        groupId: String,
        rawText: String,
        attachments: List<GroupAttachment> = emptyList(),
    ) {
        val text = rawText.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        startDeliveryProcessing()
        userAdmission.send(groupId, text, attachments.toList())
    }

    /**
     * 定时任务入口：有群聊时按普通群消息回复（含流式占位），没有群聊则直接投递到 Bot 会话。
     */
    suspend fun triggerScheduled(bot: Bot, instruction: String, groupId: String?): Boolean {
        val trigger = "【定时任务】$instruction"
        val group = if (groupId != null) {
            groupRepository.getGroup(groupId) ?: return false
        } else groupRepository.observeGroupsForBot(bot.id).first().firstOrNull()
        if (group != null) {
            val history = runCatching { groupRepository.observeMessages(group.id).first() }
                .getOrDefault(emptyList())
            if (groupRepository.getMembers(group.id).none { it.id == bot.id }) return false
            val admitted = CompletableDeferred<Boolean>()
            val job = dispatchToBot(group, bot, trigger, formatGroupHistory(group, history), groupRepository.getMembers(group.id), admission = admitted)
            return try { admitted.await() } catch (cancelled: CancellationException) {
                if (!admitted.isCompleted) job?.cancel()
                throw cancelled
            }
        }
        orchestrator.ensureSession(bot)
        return sessionControl.submit(bot.sessionId, trigger) is PromptSubmission.Accepted
    }

    suspend fun postSystemNotification(groupId: String, text: String, taskId: String? = null) {
        groupRepository.postMessage(
            GroupMessage(
                id = UUID.randomUUID().toString(),
                groupId = groupId,
                senderId = SYSTEM_SENDER_ID,
                senderName = "系统通知",
                senderType = GroupSenderType.SYSTEM,
                content = text,
                timestamp = System.currentTimeMillis(),
                delegatedTaskId = taskId,
            ),
        )
    }

    /**
     * 主控把任务交给成员在群聊**前台**执行：成员以自己的头像/会话流式输出与工具步骤，
     * 不再走后台子智能体 Lane。挂起等待本轮结束并返回结果，供 wait=true 的委派直接验收。
     */
    suspend fun runAssignment(
        group: GroupChat,
        target: Bot,
        requester: Bot,
        task: String,
        taskId: String,
        writePaths: List<String> = emptyList(),
        independentSessionId: String? = null,
        onAdmitted: suspend (String?) -> Unit = {},
    ): GroupTurnOutcome {
        val history = runCatching { groupRepository.observeMessages(group.id).first() }
            .getOrDefault(emptyList())
        val members = groupRepository.getMembers(group.id)
        val trigger = buildString {
            appendLine("【主控 ${requester.name} 委派】")
            appendLine("请以你在本群的身份直接执行并实时回复进度与结果，不要只给计划；")
            appendLine("需要时可以调用你的工具，完成后给出可验收的结论。")
            if (writePaths.isNotEmpty()) {
                appendLine("建议写入范围：${writePaths.joinToString("、")}")
            }
            appendLine()
            appendLine(task)
        }
        val result = CompletableDeferred<GroupTurnOutcome>()
        val job = replies.launch(group.id, independentSessionId?.let { "assignment:$taskId" } ?: "${target.id}:${group.id}") {
            result.complete(
                responder.respond(
                    group = group,
                    bot = target,
                    trigger = trigger,
                    history = formatGroupHistory(group, history),
                    members = members,
                    imageUrls = emptyList(),
                    admission = null,
                    delegatedTaskId = taskId,
                    sessionOverride = independentSessionId,
                    onAdmitted = onAdmitted,
                ),
            )
        }
        if (job == null) {
            return GroupTurnOutcome("❌ 群聊正在停止，任务未开始", failed = true)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !result.isCompleted) result.completeExceptionally(cause)
        }
        return try {
            result.await()
        } catch (cancelled: CancellationException) {
            job.cancel()
            throw cancelled
        }
    }

    /**
     * 停止群内所有正在运行的成员会话，并把流式占位改为“已停止”。
     * 后台委派由 [top.tianyan.app.harness.bot.BotAssignmentTracker] 单独取消。
     */
    suspend fun cancelGroup(groupId: String) {
        roundCoordinator.cancel(groupId)
        groupRepository.cancelDeliveries(groupId)
        replies.cancel(groupId)
        val states = sessionControl.sessionRunStates.value
        val streaming = groupRepository.observeMessages(groupId).first().filter { it.isStreaming }
        val streamingSenders = streaming.map { it.senderId }.toSet()
        groupRepository.getMembers(groupId).forEach { bot ->
            val sessionIds = buildList {
                addAll(orchestrator.groupSessionId(bot.id, groupId)?.let(::listOf).orEmpty())
            }.distinct()
            val running = sessionIds.any { sessionId ->
                val state = states[sessionId]
                state == SessionRunState.RUNNING || state == SessionRunState.WAITING_APPROVAL
            }
            if (running || bot.id in streamingSenders) {
                sessionIds.forEach { sessionId ->
                    sessionControl.cancel(sessionId)
                }
            }
        }
        streaming.forEach { groupRepository.updateStreamingMessage(it.id, STOPPED_MARKER, false, it.steps) }
    }

    /** 按底层会话定位并取消所属群聊的持久收件与排队任务。 */
    suspend fun cancelForSession(sessionId: String): Boolean {
        val groupId = orchestrator.groupIdForSession(sessionId) ?: return false
        cancelGroup(groupId)
        return true
    }

    /** 停止全部群聊的任务执行与排队收件。 */
    suspend fun cancelAll() {
        val groups = groupRepository.observeGroups().first()
        groups.forEach { cancelGroup(it.id) }
    }

    private fun dispatchToBot(
        group: GroupChat,
        bot: Bot,
        trigger: String,
        history: String,
        members: List<Bot>,
        imageUrls: List<String> = emptyList(),
        admission: CompletableDeferred<Boolean>? = null,
    ): kotlinx.coroutines.Job? {
        // 同一 Bot 在不同群可以并行；锁按 (bot, group) 维度隔离。
        val job = replies.launch(group.id, "${bot.id}:${group.id}") {
            responder.respond(group, bot, trigger, history, members, imageUrls, admission)
        }
        if (job == null) admission?.complete(false)
        job?.invokeOnCompletion { admission?.complete(false) }
        return job
    }

    private companion object {
        const val USER_SENDER_ID = "user_me"
        const val SYSTEM_SENDER_ID = "system"
        const val STOPPED_MARKER = "⏹ 已停止"
    }
}

/** Explicit mentions always win; otherwise the group's reply mode decides. */
internal fun openingResponders(
    members: List<Bot>,
    mentions: Set<String>,
    adminBotId: String,
    mode: GroupReplyMode,
): List<Bot> {
    if (mentions.isNotEmpty()) {
        val targeted = members.filter { bot -> mentions.any { it.equals(bot.name, ignoreCase = true) } }
        if (targeted.isNotEmpty()) return targeted
        // 用户的 @所有人 唤醒全体成员（管理员优先）；成员回复中的全体保留字
        // 由 appendRoundMentions 单独约束为仅管理员生效，这里只处理用户入口。
        if (mentions.any { it in MentionExtractor.ALL_KEYWORDS }) {
            val admin = members.firstOrNull { it.id == adminBotId }
            return (if (admin == null) members else listOf(admin) + members.filter { it.id != admin.id })
                .take(ROUND_MAX_TOTAL_TURNS)
        }
    }
    val admin = members.firstOrNull { it.id == adminBotId }
    return when (mode) {
        GroupReplyMode.ADMIN_FIRST -> listOfNotNull(admin ?: members.firstOrNull())
        GroupReplyMode.ALL_MEMBERS -> (if (admin == null) members else listOf(admin) + members.filter { it.id != admin.id })
            .take(ROUND_MAX_TOTAL_TURNS)
    }
}
