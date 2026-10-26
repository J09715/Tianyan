package top.tianyan.app.harness.groupchat

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupLateReplyStatus
import top.tianyan.app.harness.session.SessionControl

/** Keeps a late group turn observable after it yields, and reattaches watchers after a restart. */
internal class GroupLateReplyWatcher(
    private val groups: GroupChatRepository,
    private val sessionControl: SessionControl,
    private val logger: AppLogger,
    private val liveReplies: MutableStateFlow<Map<String, GroupReplyProgress>>,
    private val scope: CoroutineScope?,
    private val taskTracker: GroupReplyTaskTracker? = null,
) {
    private val observer = GroupReplyObserver(sessionControl, logger, taskTracker)

    suspend fun watch(
        group: GroupChat,
        bot: Bot,
        sessionId: String,
        baseline: Set<String>,
        startedAt: Long,
        baselineOutputRevision: Long,
        placeholderId: String,
        callerBotId: String?,
        onLateReply: suspend (String) -> Unit,
        taskId: String? = null,
    ) {
        val lateReplyId = UUID.randomUUID().toString()
        groups.insertLateReply(
            GroupLateReply(
                id = lateReplyId,
                groupId = group.id,
                workerId = bot.id,
                workerName = bot.name,
                sessionId = sessionId,
                placeholderId = placeholderId,
                callerBotId = callerBotId,
                createdAt = System.currentTimeMillis(),
                status = GroupLateReplyStatus.WATCHING,
                taskId = taskId,
            ),
        )
        watchInternal(
            group = group,
            bot = bot,
            sessionId = sessionId,
            baseline = baseline,
            startedAt = startedAt,
            baselineOutputRevision = baselineOutputRevision,
            placeholderId = placeholderId,
            lateReplyId = lateReplyId,
            taskId = taskId,
            onLateReply = onLateReply,
        )
    }

    /** Reattach late-reply watchers persisted before a restart. */
    suspend fun recover(onLateReply: suspend (GroupLateReply, String) -> Unit) {
        val scope = scope ?: return
        val watching = try {
            groups.watchingLateReplies()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupChatDispatcher] 读取迟到回复失败", failure)
            return
        }
        watching.forEach { reply ->
            scope.launch {
                try {
                    val group = groups.getGroup(reply.groupId)
                        ?: return@launch fail(reply, "群聊已删除")
                    val bot = groups.getMembers(reply.groupId).firstOrNull { it.id == reply.workerId }
                        ?: return@launch fail(reply, "成员已离开群聊")
                    val messages = groups.observeMessages(reply.groupId).first()
                    val placeholder = messages.firstOrNull { it.id == reply.placeholderId }
                        ?: return@launch fail(reply, "占位消息已删除")
                    if (!placeholder.isStreaming) {
                        groups.finishLateReply(reply.id, GroupLateReplyStatus.DONE)
                        return@launch
                    }
                    sessionControl.prepareRemoteSession(reply.sessionId)
                    val sessionMessages = sessionControl.persistedMessages(reply.sessionId)
                    val state = sessionControl.sessionRunStates.value[reply.sessionId]
                    if (reply.taskId == null && sessionMessages.isEmpty() &&
                        (state == null || state == SessionRunState.IDLE)) {
                        return@launch fail(reply, "执行会话已丢失，结果待核对")
                    }
                    // awaitReply filters session messages, so the baseline must use session IDs too.
                    val baseline = sessionMessages
                        .filter { it.createdAt < placeholder.timestamp }
                        .map { it.id }
                        .toSet()
                    watchInternal(
                        group = group,
                        bot = bot,
                        sessionId = reply.sessionId,
                        baseline = baseline,
                        startedAt = placeholder.timestamp,
                        baselineOutputRevision = 0,
                        placeholderId = reply.placeholderId,
                        lateReplyId = reply.id,
                        taskId = reply.taskId,
                        onLateReply = { text -> onLateReply(reply, text) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.e("[GroupChatDispatcher] 迟到回复恢复失败", failure)
                    fail(reply, "恢复失败，结果待核对")
                }
            }
        }
    }

    private fun watchInternal(
        group: GroupChat,
        bot: Bot,
        sessionId: String,
        baseline: Set<String>,
        startedAt: Long,
        baselineOutputRevision: Long,
        placeholderId: String,
        lateReplyId: String?,
        taskId: String?,
        onLateReply: suspend (String) -> Unit,
    ) {
        val scope = scope ?: return
        scope.launch {
            val outcome = try {
                withGroupReplyCheckpoints(persist = { progress ->
                    groups.updateStreamingMessage(placeholderId, progress.content, true, progress.steps, progress.reasoning)
                }) { checkpoint ->
                    observer.awaitReply(
                        sessionId, bot, baseline, startedAt, baselineOutputRevision, completionOnly = true, taskId = taskId,
                    ) { progress ->
                        liveReplies.update { it + (placeholderId to progress) }
                        checkpoint(progress)
                    }
                }
            } catch (cancelled: CancellationException) {
                liveReplies.update { it - placeholderId }
                throw cancelled
            } catch (failure: Exception) {
                logger.e("[GroupChatDispatcher] ${bot.name} 迟到回复等待失败", failure)
                null
            }
            val latest = outcome?.candidate?.text?.trim().orEmpty()
            val failed = outcome == null || outcome.replyTimedOut || outcome.failed || outcome.interrupted ||
                (outcome.candidate == null && outcome.steps.isEmpty())
            val finalText = when {
                outcome?.replyTimedOut == true ->
                    "❌ 后台执行超过 ${GroupReplyTimeoutPolicy.REPLY_TIMEOUT_MS / 60_000} 分钟，已请求停止；结果待核对"
                failed -> "❌ 群聊成员的后台执行未能完成" +
                    latest.takeIf { it.isNotBlank() }?.let { "\n最后输出：$it" }.orEmpty()
                latest.isBlank() -> "（工作完成，未补充文字）"
                else -> latest
            }
            try {
                val stillStreaming = groups.observeMessages(group.id).first()
                    .any { it.id == placeholderId && it.isStreaming }
                if (stillStreaming && groups.getGroup(group.id) != null) {
                    groups.updateStreamingMessage(
                        placeholderId, finalText, false, outcome?.steps.orEmpty(), outcome?.reasoning.orEmpty(),
                    )
                    liveReplies.update { it - placeholderId }
                    if (!failed) onLateReply(finalText)
                } else {
                    liveReplies.update { it - placeholderId }
                }
                if (lateReplyId != null) {
                    groups.finishLateReply(
                        lateReplyId,
                        if (failed) GroupLateReplyStatus.FAILED else GroupLateReplyStatus.DONE,
                        if (failed) finalText.take(500) else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                liveReplies.update { it - placeholderId }
                throw cancelled
            } catch (failure: Exception) {
                logger.w("[GroupChatDispatcher] 迟到回复收尾失败", failure)
                liveReplies.update { it - placeholderId }
            }
        }
    }

    private suspend fun fail(reply: GroupLateReply, reason: String) {
        val stillStreaming = try {
            groups.observeMessages(reply.groupId).first()
                .any { it.id == reply.placeholderId && it.isStreaming }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupChatDispatcher] 迟到回复占位读取失败", failure)
            false
        }
        if (stillStreaming) {
            try {
                groups.updateStreamingMessage(reply.placeholderId, "❌ $reason", false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.w("[GroupChatDispatcher] 迟到回复占位收尾失败", failure)
            }
        }
        liveReplies.update { it - reply.placeholderId }
        try {
            groups.finishLateReply(reply.id, GroupLateReplyStatus.FAILED, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupChatDispatcher] 迟到回复失败状态落库失败", failure)
        }
    }
}
