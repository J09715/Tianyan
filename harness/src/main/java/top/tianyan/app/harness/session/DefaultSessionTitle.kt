package top.tianyan.app.harness.session

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.harness.PendingMessage
import top.tianyan.app.harness.task.AgentStateMachine

/**
 * 从用户指令抽出会话标题。
 *
 * 与 durable task 标题一致：取第一条非空行，压成单行，最多 [MAX_CHARS] 个字符。
 */
object DefaultSessionTitle {
    const val MAX_CHARS = 80

    private val PLACEHOLDERS = setOf("新建会话", "新会话", "new session")

    fun isPlaceholder(title: String): Boolean = title.trim().lowercase(Locale.ROOT) in PLACEHOLDERS

    fun fromInstruction(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return line.replace(WHITESPACE, " ").take(MAX_CHARS)
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * 默认标题的会话在首条指令受理时改名。
 *
 * 已经有用户消息、或标题已被用户改过的会话保持原名。同一进程内只尝试一次，
 * 避免首条消息尚未落库时，紧接着的第二条指令把标题覆盖掉。
 */
class DefaultSessionTitleAdopter(
    private val sessions: HarnessSessionRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val hasUserMessage: suspend (String) -> Boolean,
) {
    private val settled = ConcurrentHashMap.newKeySet<String>()

    suspend fun adopt(sessionId: String, title: String) {
        if (sessionId in settled || !settled.add(sessionId)) return
        val session = sessions.findById(sessionId)
        if (session == null) {
            settled.remove(sessionId)
            return
        }
        if (!DefaultSessionTitle.isPlaceholder(session.title) || hasUserMessage(sessionId)) return
        if (title.isBlank()) {
            settled.remove(sessionId)
            return
        }
        if (DefaultSessionTitle.isPlaceholder(title)) return
        sessions.rename(sessionId, title, nowMs())
    }
}

/** 入队 durable task，并在默认会话上套用指令标题。 */
class QueuedInstructionRecorder(
    private val tasks: AgentStateMachine,
    sessions: HarnessSessionRepository,
    private val log: suspend (String, String, String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    hasUserMessage: suspend (String) -> Boolean,
) {
    private val titles = DefaultSessionTitleAdopter(sessions, nowMs, hasUserMessage)

    suspend fun record(sessionId: String, pending: PendingMessage) {
        val taskId = pending.taskId ?: return
        val description = pending.text.ifBlank { "用户发送了 ${pending.imageUrls.size} 张图片" }
        val title = DefaultSessionTitle.fromInstruction(description)
        tasks.createQueued(
            id = taskId,
            sessionId = sessionId,
            title = title,
            description = description,
            nowMs = pending.createdAt,
            groupWorkId = pending.groupWorkId, groupId = pending.groupId,
        )
        titles.adopt(sessionId, title)
        log(sessionId, "DurableTaskQueued", "taskId=$taskId")
    }
}
