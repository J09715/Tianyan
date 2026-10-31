package top.tianyan.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.harness.bot.BotAssignmentRuntime
import top.tianyan.app.harness.groupchat.GroupChatDispatcher
import top.tianyan.app.harness.session.SessionControl

/** 处理通知栏【回复】输入框：按会话类型路由指令，保障任务续办记录与群气泡分发。 */
class AgentReplyReceiver : BroadcastReceiver(), KoinComponent {

    val harnessLoop: SessionControl by inject()
    val groupDispatcher: GroupChatDispatcher? get() = runCatching { getKoin().getOrNull<GroupChatDispatcher>() }.getOrNull()
    val assignmentRuntime: BotAssignmentRuntime? get() = runCatching { getKoin().getOrNull<BotAssignmentRuntime>() }.getOrNull()
    val botGroupSessions: BotGroupSessionRepository? get() = runCatching { getKoin().getOrNull<BotGroupSessionRepository>() }.getOrNull()
    val botRepository: PersonaBotRepository? get() = runCatching { getKoin().getOrNull<PersonaBotRepository>() }.getOrNull()

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AgentForegroundService.KEY_REPLY)
            ?.toString()
            ?.trim()
        val targetSessionId = intent.getStringExtra(AgentForegroundService.EXTRA_SESSION_ID)
        if (reply.isNullOrBlank()) return

        // 先拉起前台服务保持后台存活
        AgentForegroundService.startFromReply(context, targetSessionId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                routeReply(reply, targetSessionId)
            } catch (failure: Exception) {
                Log.e(TAG, "回复路由执行失败，降级回退到默认会话提交", failure)
                harnessLoop.send(reply, targetSessionId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun routeReply(
        reply: String,
        targetSessionId: String?,
        assignmentLookup: suspend (String) -> BotAssignment? = { assignmentRuntime?.taskForSession(it) },
        continueTaskHandler: suspend (String, String, String) -> Unit = { id, gId, text -> assignmentRuntime?.continueTask(id, gId, text) },
        groupLookup: suspend (String) -> Pair<String, String?>? = { sessionId ->
            val gId = botGroupSessions?.groupIdForSession(sessionId)
            if (gId != null && groupDispatcher != null) {
                val bId = botGroupSessions?.botIdForSession(sessionId)
                val bName = bId?.let { botRepository?.getBot(it)?.name }
                gId to bName
            } else null
        },
        groupSendHandler: suspend (String, String) -> Unit = { gId, text -> groupDispatcher?.onUserSend(gId, text) },
        fallbackSendHandler: (String, String?) -> Unit = { text, sessId -> harnessLoop.send(text, sessId) },
    ) {
        if (targetSessionId.isNullOrBlank()) {
            fallbackSendHandler(reply, targetSessionId)
            return
        }

        // 1. 独立委派任务：调用 continueTask 记录任务轮次并更新工作面板状态
        val task = assignmentLookup(targetSessionId)
        val taskGroupId = task?.groupId
        if (task != null && task.independent && taskGroupId != null) {
            continueTaskHandler(task.id, taskGroupId, reply)
            return
        }

        // 2. 群聊 Bot 会话：通过 GroupChatDispatcher 投递，包含用户气泡与群消息分发
        val groupInfo = groupLookup(targetSessionId)
        if (groupInfo != null) {
            val (groupId, botName) = groupInfo
            val text = if (!botName.isNullOrBlank() && !reply.startsWith("@")) "@$botName $reply" else reply
            groupSendHandler(groupId, text)
            return
        }

        // 3. 独立/主对话：直接提交给会话
        fallbackSendHandler(reply, targetSessionId)
    }

    companion object {
        private const val TAG = "AgentReplyReceiver"
    }
}
