package top.tianyan.app.harness.groupchat

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupDelivery
import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl

/** Projects a single native turn into the group timeline; admission is recorded before observing output. */
internal class GroupBotResponder(
    private val groupRepository: GroupChatRepository,
    private val prepareSession: suspend (Bot, String) -> String,
    private val sessionControl: SessionControl,
    private val logger: AppLogger,
    private val liveReplies: MutableStateFlow<Map<String, GroupReplyProgress>>,
    private val freshness: GroupReplyFreshness? = null,
    private val lateReplyScope: CoroutineScope? = null,
    private val workGraceMs: Long = WORK_GRACE_MS,
    private val taskTracker: GroupReplyTaskTracker? = null,
    private val contextScope: GroupContextScope? = null,
) {
    private val observer = GroupReplyObserver(sessionControl, logger, taskTracker)
    private val lateReplyWatcher = GroupLateReplyWatcher(
        groupRepository, sessionControl, logger, liveReplies, lateReplyScope, taskTracker,
    )
    suspend fun respond(
        group: GroupChat,
        bot: Bot,
        trigger: String,
        history: String,
        members: List<Bot>,
        imageUrls: List<String>,
        admission: CompletableDeferred<Boolean>?,
        delegatedTaskId: String? = null,
        workReceiptId: String? = null,
        deliveryId: String? = null,
        sessionOverride: String? = null,
        onAdmitted: suspend (String?) -> Unit = {},
        silentPass: Boolean = false,
        promptHistory: String? = null,
        lateReplyCallerId: String? = null,
        onSubmitted: suspend () -> Unit = {},
        onLateReply: (suspend (String) -> Unit)? = null,
    ): GroupTurnOutcome {
        var activeSessionId: String? = null
        var submissionAttempted = false
        var admittedTaskId: String? = null
        var contextBinding: GroupReplyFreshness.Binding? = null
        val placeholderId = deliveryId?.let { "delivery-reply:$it" } ?: UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val limits = group.executionLimits
        val executionPolicy = GroupExecutionPolicy(limits.warnAfterMs, limits.stopAfterMs)
        try {
            groupRepository.postMessage(
                GroupMessage(
                    id = placeholderId,
                    groupId = group.id,
                    senderId = bot.id,
                    senderName = bot.name,
                    senderType = GroupSenderType.BOT,
                    content = "",
                    timestamp = startedAt,
                    isStreaming = true,
                    delegatedTaskId = delegatedTaskId,
                ),
            )
            val sessionReady = runCatching { sessionOverride ?: prepareSession(bot, group.id) }
            if (sessionReady.isFailure) {
                val error = sessionReady.exceptionOrNull()
                logger.e("[GroupChatDispatcher] Bot ${bot.name} 群维度会话准备失败", error)
                val text = "❌ 无法准备会话：${error?.message}"
                groupRepository.updateStreamingMessage(placeholderId, text, false)
                if (deliveryId != null) groupRepository.finishDelivery(deliveryId, GroupDelivery.REJECTED, text)
                return GroupTurnOutcome(text, failed = true)
            }
            val sessionId = sessionReady.getOrThrow()
            activeSessionId = sessionId
            currentCoroutineContext().ensureActive()
            val baseline = sessionControl.prepareRemoteSession(sessionId).map { it.id }.toSet()
            val baselineOutputRevision = sessionControl.modelOutputRevisions.value[sessionId] ?: 0L
            contextBinding = freshness?.begin(sessionId, group, bot.id, placeholderId)
            // A round passes only unseen lines; other deliveries keep the binding's full view.
            val resolvedHistory = if (promptHistory != null) contextScope?.history(group, bot.id, sessionId, true) ?: promptHistory
                else contextBinding?.history ?: contextScope?.history(group, bot.id, sessionId, false) ?: history
            val prompt = buildGroupPrompt(group, bot, trigger, resolvedHistory, members, groupRepository.getTeam(group.id), groupRepository.getGovernance(group.id))
            if (deliveryId != null) {
                check(groupRepository.prepareDelivery(deliveryId, sessionId)) { "收件已停止、成员已移出或来源已清除" }
            }
            if (sessionOverride != null) require(groupRepository.getMembers(group.id).any { it.id == bot.id }) {
                "执行成员已移出来源群聊，未提交任务"
            }
            submissionAttempted = true
            val inheritedWork = contextScope?.workForReceipt(workReceiptId ?: delegatedTaskId)
            val work = inheritedWork?.let { GroupWorkContext(it, group.id) }
                ?: currentCoroutineContext()[GroupWorkContext] ?: GroupWorkContext(placeholderId, group.id)
            val submission = withContext(work) { sessionControl.submit(sessionId, prompt, imageUrls) }
            admittedTaskId = (submission as? PromptSubmission.Accepted)?.taskId
            if (deliveryId != null && submission is PromptSubmission.Accepted) {
                check(groupRepository.admitDelivery(deliveryId, sessionId, submission.taskId)) { "群消息受理回执未保存" }
            }
            if (submission is PromptSubmission.Accepted) {
                onAdmitted(submission.taskId)
                try {
                    onSubmitted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.w("[GroupChatDispatcher] ${bot.name} 提交后记录 seen 失败", failure)
                }
            }
            currentCoroutineContext().ensureActive()
            admission?.complete(submission is PromptSubmission.Accepted)
            if (submission !is PromptSubmission.Accepted) {
                val reason = (submission as? PromptSubmission.Rejected)?.reason?.name ?: "提交异常"
                val text = "❌ 无法开始回复：$reason"
                groupRepository.updateStreamingMessage(placeholderId, text, false)
                if (deliveryId != null) groupRepository.finishDelivery(deliveryId, GroupDelivery.REJECTED, text)
                return GroupTurnOutcome(text, failed = true)
            }

            var sawWork = false
            var warned = false
            var lastProgress: GroupReplyProgress? = null
            suspend fun observe(completionOnly: Boolean): GroupReplyObserver.ReplyOutcome =
                withGroupReplyCheckpoints(persist = { progress ->
                    groupRepository.updateStreamingMessage(placeholderId, progress.content, true, progress.steps, progress.reasoning)
                }) { checkpoint ->
                    observer.awaitReply(
                        sessionId, bot, baseline, startedAt, baselineOutputRevision, completionOnly, admittedTaskId,
                        executionPolicy = if (delegatedTaskId != null && sessionOverride != null) executionPolicy else null,
                    ) { progress ->
                        lastProgress = progress
                        if (progress.executionWarning && !warned) {
                            warned = true
                            groupRepository.postMessage(GroupMessage(
                                id = "execution-warning:$placeholderId", groupId = group.id,
                                senderId = "system", senderName = "系统通知", senderType = GroupSenderType.SYSTEM,
                                content = "⚠️ ${bot.name} 已连续${limits.warnAfterMs / 1_000}秒没有工具执行进展；模型输出不代表任务推进。${limits.stopAfterMs / 1_000}秒仍无进展将停止本轮并回报原负责人。",
                                timestamp = System.currentTimeMillis(), delegatedTaskId = delegatedTaskId))
                        }
                        if (progress.steps.isNotEmpty() || progress.contentText.hasText) sawWork = true
                        liveReplies.update { it + (placeholderId to progress) }
                        checkpoint(progress)
                    }
                }

            val outcome = if (lateReplyScope != null && onLateReply != null) {
                val first = withTimeoutOrNull(workGraceMs) { observe(completionOnly = false) }
                val runState = sessionControl.sessionRunStates.value[sessionId]
                val waitingApproval = runState == SessionRunState.WAITING_APPROVAL
                if (first == null && (sawWork || waitingApproval) &&
                    (runState == SessionRunState.RUNNING || waitingApproval)) {
                    lastProgress?.let { progress ->
                        groupRepository.updateStreamingMessage(
                            placeholderId, progress.content, true, progress.steps, progress.reasoning,
                        )
                    }
                    lateReplyWatcher.watch(
                        group = group,
                        bot = bot,
                        sessionId = sessionId,
                        baseline = baseline,
                        startedAt = startedAt,
                        baselineOutputRevision = baselineOutputRevision,
                        placeholderId = placeholderId,
                        callerBotId = lateReplyCallerId,
                        onLateReply = onLateReply,
                        taskId = admittedTaskId,
                    )
                    return GroupTurnOutcome("", failed = false, working = true, messageId = placeholderId)
                }
                first ?: observe(completionOnly = false)
            } else {
                observe(completionOnly = false)
            }
            val latest = outcome.candidate
            val content = latest?.text?.trim().orEmpty()
            val cleanPass = silentPass && !outcome.failed && !outcome.replyTimedOut &&
                !outcome.firstTokenTimedOut && !outcome.stalled && !outcome.interrupted &&
                isGroupPass(content)
            if (cleanPass) {
                groupRepository.deleteMessage(placeholderId)
                return GroupTurnOutcome("", failed = false, passed = true, messageId = placeholderId)
            }
            val finalText = when {
                outcome.replyTimedOut -> "❌ 本轮超过 ${GroupReplyTimeoutPolicy.REPLY_TIMEOUT_MS / 60_000} 分钟，已请求停止；执行结果待核对，请检查已有工具结果"
                outcome.noExecutionProgress -> "⛔ 连续${limits.stopAfterMs / 1_000}秒没有有效执行进展，本轮已停止；任务受阻，请原负责人核验原会话与已有产物后继续。"
                outcome.firstTokenTimedOut ->
                    "❌ ${FIRST_TOKEN_TIMEOUT_MS / 1000} 秒未收到模型正文、推理或工具调用，已请求停止；执行结果待核对"
                outcome.stalled -> "❌ 连续 ${FIRST_TOKEN_TIMEOUT_MS / 1000} 秒无新输出，已请求停止；执行结果待核对，请先检查已有产物"
                outcome.failed -> "❌ 本轮执行失败，请核对会话错误与已有产物" + content.takeIf { it.isNotBlank() }?.let { "\n最后输出：$it" }.orEmpty()
                outcome.interrupted -> "⏹ 本轮已中断，执行结果待核对" + content.takeIf { it.isNotBlank() }?.let { "\n最后输出：$it" }.orEmpty()
                content.isNotBlank() -> content
                latest == null -> "❌ 本轮未产生回复，请稍后重试"
                else -> "（无文本回复）"
            }
            groupRepository.updateStreamingMessage(placeholderId, finalText, false, outcome.steps, outcome.reasoning)
            awaitSettledPlaceholder(group.id, placeholderId)
            return GroupTurnOutcome(
                text = finalText,
                failed = outcome.replyTimedOut || outcome.firstTokenTimedOut || outcome.stalled || outcome.failed || outcome.interrupted || outcome.noExecutionProgress || content.isBlank(),
                uncertain = outcome.replyTimedOut || outcome.firstTokenTimedOut || outcome.stalled || outcome.interrupted,
                messageId = placeholderId,
                blockedReason = finalText.takeIf { outcome.noExecutionProgress },
                nativeTaskId = admittedTaskId,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (submissionAttempted) activeSessionId?.let { cancelOwned(it, admittedTaskId) }
                val progress = liveReplies.value[placeholderId]
                groupRepository.updateStreamingMessage(placeholderId, STOPPED_MARKER, false, progress?.steps.orEmpty(), progress?.reasoning)
                awaitSettledPlaceholder(group.id, placeholderId)
            }
            throw cancelled
        } catch (failure: Exception) {
            if (submissionAttempted) activeSessionId?.let { cancelOwned(it, admittedTaskId) }
            admission?.completeExceptionally(failure)
            if (deliveryId != null) groupRepository.finishDelivery(deliveryId,
                if (submissionAttempted) GroupDelivery.UNKNOWN else GroupDelivery.REJECTED,
                if (submissionAttempted) "群消息处理结果待核对，请检查会话记录" else "群消息准备失败，未提交执行")
            logger.e("[GroupChatDispatcher] 群聊回复失败", failure)
            val text = "❌ 回复失败：${failure.message}"
            groupRepository.updateStreamingMessage(placeholderId, text, false)
            awaitSettledPlaceholder(group.id, placeholderId)
            return GroupTurnOutcome(text, failed = true, uncertain = submissionAttempted)
        } finally {
            val binding = contextBinding
            if (binding != null && activeSessionId != null) freshness?.end(activeSessionId, binding)
            liveReplies.update { it - placeholderId }
            admission?.complete(false)
        }
    }

    suspend fun recoverLateReplies(onLateReply: suspend (GroupLateReply, String) -> Unit) =
        lateReplyWatcher.recover(onLateReply)

    private suspend fun cancelOwned(sessionId: String, taskId: String?) {
        if (taskId != null && taskTracker != null) taskTracker.cancelIfActive(sessionId, taskId)
        else sessionControl.cancel(sessionId)
    }

    private suspend fun awaitSettledPlaceholder(groupId: String, messageId: String) {
        // Keep the live projection until Room emits a settled row, avoiding a blank-frame flash.
        groupRepository.observeMessages(groupId).first { rows -> rows.none { it.id == messageId && it.isStreaming } }
    }

    private companion object {
        const val STOPPED_MARKER = "⏹ 已停止"
        const val FIRST_TOKEN_TIMEOUT_MS = GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS
        const val WORK_GRACE_MS = 15_000L
    }
}
