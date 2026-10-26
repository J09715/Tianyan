package top.tianyan.app.harness.bot.diagnostics

import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessLaneEntity
import top.tianyan.app.core.database.HarnessOperationEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.session.SessionControl

/**
 * 一键导出群聊诊断：群消息 + 每个 Bot 的完整会话（含工具调用/结果）+ 相关运行日志。
 * 输出为 Markdown，便于直接分享排查“哪一步出错”。
 */
class BotDiagnosticsExporter(
    private val groupRepository: GroupChatRepository,
    private val assignmentRepository: BotAssignmentRepository,
    private val groupSessionRepository: BotGroupSessionRepository,
    private val sessionControl: SessionControl,
    private val runtimeRepository: HarnessRuntimeRepository,
    private val json: Json,
    private val logger: AppLogger,
    private val nativeTasks: top.tianyan.app.core.database.task.AgentTaskRepository? = null,
) {
    suspend fun exportGroup(groupId: String, destination: File): File {
        val group = requireNotNull(groupRepository.getGroup(groupId)) { "群聊不存在" }
        val members = groupRepository.getMembers(groupId)
        val messages = groupRepository.observeMessages(groupId).first()
        val assignments = runCatching { assignmentRepository.observeForGroup(groupId).first() }
            .getOrDefault(emptyList())
        val deliveries = groupRepository.listDeliveries(groupId)
        val runStates = sessionControl.sessionRunStates.value
        val statuses = sessionControl.sessionStatuses.value
        val transcripts = mutableMapOf<String, List<HarnessMessage>>()
        val transcriptErrors = mutableMapOf<String, String>()
        val lanesByBot = mutableMapOf<String, List<HarnessLaneEntity>>()
        val operationsByBot = mutableMapOf<String, List<HarnessOperationEntity>>()
        val extraEntriesByBot = mutableMapOf<String, List<HarnessEntryEntity>>()
        val sessionIds = mutableMapOf<String, String>()
        members.forEach { bot ->
            val sessionId = runCatching { groupSessionRepository.sessionIdFor(bot.id, groupId) }
                .getOrNull()
                ?: return@forEach
            sessionIds[bot.id] = sessionId
            runCatching { sessionControl.persistedMessages(sessionId) }
                .onSuccess { transcripts[bot.id] = it }
                .onFailure { transcriptErrors[bot.id] = it.message.orEmpty() }
            lanesByBot[bot.id] = runCatching { runtimeRepository.observeLanes(sessionId).first() }
                .getOrDefault(emptyList())
            operationsByBot[bot.id] = runtimeRepository.listActiveOperations(sessionId)
            val knownIds = transcripts[bot.id].orEmpty().mapTo(mutableSetOf()) { it.id }
            extraEntriesByBot[bot.id] = runCatching { runtimeRepository.listEntries(sessionId) }
                .getOrDefault(emptyList())
                .filterNot { it.id in knownIds }
                .takeLast(RUNTIME_ENTRY_LIMIT)
        }

        val report = buildString {
            appendLine("# 天衍群聊诊断导出")
            appendLine()
            appendLine("- 导出时间：${stamp(System.currentTimeMillis())}")
            appendLine("- 设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- 群聊：${group.name} (`${group.id}`)")
            appendLine("- 群聊说明：${group.description.ifBlank { "（空）" }}")
            appendLine("- 管理员 Bot：${members.firstOrNull { it.id == group.adminBotId }?.name ?: group.adminBotId}")
            appendLine("- 成员数：${members.size}")
            appendLine()
            appendLine("> 注意：导出内容包含群聊与 Bot 会话原文（含工具输出），请仅私下发送给可信对象。")
            appendLine()
            appendLine("## 成员与运行态")
            members.forEach { bot ->
                appendLine()
                appendLine("### ${bot.name}（${bot.role}）${if (bot.isMainBot) " · 主控" else ""}")
                appendLine("- botId：`${bot.id}`")
                val sessionId = sessionIds[bot.id]
                appendLine("- sessionId：${sessionId?.let { "`$it`（群维度会话）" } ?: "尚未建立群会话"}")
                appendLine("- modelId：${bot.modelId ?: "默认"} · modelVariant：${bot.modelVariant ?: "档案默认"}")
                appendLine("- runState：${runStates[sessionId] ?: "未加载（不代表任务空闲）"}")
                appendLine("- status：${statuses[sessionId] ?: "—"}")
                assignments.filter { it.botId == bot.id && it.independent }.forEach { task ->
                    appendLine("- 独立任务会话：`${task.sessionId ?: "缺失"}` · task=${task.id} · status=${task.status} · runState=${runStates[task.sessionId] ?: "未加载"}")
                }
                operationsByBot[bot.id].orEmpty().forEach { operation ->
                    appendLine("- 活动执行：${operation.laneName} · ${operation.status}/${operation.phase} · " +
                        "updated=${stamp(operation.updatedAt)} · effect=${operation.pendingEffectKind ?: "—"}")
                }
                appendLine("- persona：${bot.systemPrompt.replace('\n', ' ').take(PERSONA_CHARS)}")
            }
            appendLine()
            appendLine("## 群聊消息（${messages.size}）")
            appendLine("> 群消息时间是消息创建／开始输出时间；任务结束时间见委派记录，不能将气泡时间当作完成时间。")
            messages.forEach { message -> appendLine(renderGroupMessage(message)) }
            appendLine()
            appendLine("## 群消息收件回执（最近 ${deliveries.size} 条）")
            deliveries.forEach { receipt ->
                appendLine("- ${receipt.id} · ${receipt.status} · Bot=${receipt.botId} · 来源=${receipt.sourceMessageId}")
                appendLine("  会话=${receipt.sessionId ?: "—"} · 执行=${receipt.taskId ?: "—"} · ${receipt.error ?: ""}")
            }
            appendLine()
            appendLine("## 委派任务（${assignments.size}）")
            if (assignments.isEmpty()) appendLine("（无委派记录）")
            assignments.forEach { assignment -> appendAssignment(assignment) }
            appendLine()
            appendLine("## 独立任务轮次与会话")
            assignments.filter { it.independent }.take(30).forEach { task ->
                appendLine("### ${task.title} · ${task.id} · ${task.status} · 第 ${task.revision} 轮")
                appendLine("原会话：${task.sessionId ?: "缺失"}")
                assignmentRepository.requestsFor(task.id).forEach { request ->
                    appendLine("- 要求 ${request.id} · ${request.status} · nativeTask=${request.nativeTaskId ?: "—"}")
                    appendLine(request.instruction.take(2_000))
                    appendLine(request.blockedReason ?: request.summary ?: request.error ?: "")
                    appendLine("报告产物：${request.artifacts.joinToString()}")
                    request.nativeTaskId?.let { id -> nativeTasks?.find(id) }?.let { native ->
                        appendLine("业务任务=${native.groupWorkId ?: "—"} · 运行阶段=${native.groupProgressJson ?: "未记录"}")
                    }
                }
                val sessionId = task.sessionId
                if (sessionId != null && assignmentRepository.assignmentForSession(sessionId)?.id == task.id) {
                    val knownIds = mutableSetOf<String>()
                    runCatching { sessionControl.persistedMessages(sessionId) }
                        .onSuccess { rows ->
                            knownIds.addAll(rows.map { it.id })
                            rows.takeLast(SESSION_MESSAGE_LIMIT).forEach { appendLine(renderSessionMessage(it)) }
                        }
                        .onFailure { appendLine("原会话读取失败：${it.message}") }
                    appendLaneSection(runtimeRepository.observeLanes(sessionId).first(), runtimeRepository.listEntries(sessionId)
                        .filterNot { it.id in knownIds }.takeLast(RUNTIME_ENTRY_LIMIT))
                    runtimeRepository.listActiveOperations(sessionId).forEach { operation ->
                        appendLine("- 任务活动执行：${operation.laneName} · ${operation.status}/${operation.phase} · updated=${stamp(operation.updatedAt)}")
                    }
                    sessionControl.providerActivities.value[sessionId]?.let { appendLine("- 模型活动：${json.encodeToString(it)}") }
                }
            }
            appendLine()
            appendLine("## Bot 会话记录")
            members.forEach { bot ->
                val all = transcripts[bot.id].orEmpty()
                val shown = all.takeLast(SESSION_MESSAGE_LIMIT)
                appendLine()
                appendLine("### ${bot.name} · ${sessionIds[bot.id] ?: "尚未建立群会话"}")
                sessionIds[bot.id]?.let { sessionControl.providerActivities.value[it] }?.let { appendLine("模型活动：${json.encodeToString(it)}") }
                transcriptErrors[bot.id]?.let { appendLine("读取失败：$it") }
                appendLine("共 ${all.size} 条，导出最近 ${shown.size} 条")
                if (shown.isEmpty()) appendLine("（无会话记录）")
                shown.forEach { message -> appendLine(renderSessionMessage(message)) }
                appendLine()
                appendLaneSection(lanesByBot[bot.id].orEmpty(), extraEntriesByBot[bot.id].orEmpty())
            }
            appendLine()
            appendLine("## 运行日志")
            appendLine()
            appendLogSection(
                "Bot / 群聊运行日志",
                scopedLogs(logger.readGeneralLogs(MAX_LOG_CHARS), sessionIds.values.toSet() + assignments.map { it.id } + assignments.mapNotNull { it.sessionId }),
            )
            appendLine()
            appendLogSection("Bot Agent 日志", scopedLogs(logger.readAgentLogs(MAX_LOG_CHARS), sessionIds.values.toSet() + assignments.mapNotNull { it.sessionId }))
        }

        destination.parentFile?.mkdirs()
        destination.writeText(report, Charsets.UTF_8)
        logger.i("[BotDiagnosticsExporter] 已导出群聊诊断: ${destination.name} (${destination.length()} bytes)")
        return destination
    }

    internal fun scopedLogs(raw: String, identifiers: Set<String>): String {
        return raw.lineSequence()
            .filter { line -> identifiers.any { it.isNotBlank() && line.contains(it) } }
            .joinToString("\n")
            .takeLast(FILTERED_LOG_CHARS)
            .ifBlank { "（无匹配日志）" }
    }

    private fun StringBuilder.appendLogSection(title: String, text: String) {
        appendLine("### $title")
        appendLine("```text")
        appendLine(text)
        appendLine("```")
    }

    private fun StringBuilder.appendLaneSection(
        lanes: List<HarnessLaneEntity>,
        entries: List<HarnessEntryEntity>,
    ) {
        appendLine("#### 后台 Lane 与运行事件（额外 ${entries.size} 条）")
        if (lanes.isEmpty() && entries.isEmpty()) {
            appendLine("（无额外 Lane 记录）")
            return
        }
        if (lanes.isNotEmpty()) {
            appendLine("Lanes：")
            lanes.forEach { lane ->
                appendLine(
                    "- ${lane.name} · model=${lane.modelId ?: "默认"} · faulted=${lane.faulted} · " +
                        "updated=${stamp(lane.updatedAt)}",
                )
            }
        }
        entries.forEach { entry ->
            val decoded = runCatching {
                json.decodeFromString(HarnessMessage.serializer(), entry.payloadJson)
            }.getOrNull()
            if (decoded != null) {
                append(renderSessionMessage(decoded))
            } else {
                appendLine(
                    "[${stamp(entry.createdAt)}] ${entry.entryType}/${entry.customType ?: "-"} " +
                        truncate(entry.payloadJson, RUNTIME_PAYLOAD_CHARS),
                )
            }
        }
    }

    private fun StringBuilder.appendAssignment(assignment: BotAssignment) {
        appendLine()
        appendLine("### ${assignment.title} · ${assignment.status} · ${assignment.botName}")
        appendLine("- id：`${assignment.id}`")
        appendLine("- botId：`${assignment.botId}` · sessionId：`${assignment.sessionId ?: "-"}`")
        appendLine("- lane：${assignment.laneName ?: "-"}")
        appendLine("- work：${assignment.rootWorkId ?: "—"} · stage：${assignment.stageId ?: "—"} · item：${assignment.workItemId ?: "—"} · owner：${assignment.reportToBotId ?: "—"}")
        appendLine(
            "- 开始：${stamp(assignment.startedAt)}" +
                (assignment.finishedAt?.let { " · 结束：${stamp(it)}" } ?: ""),
        )
        if (assignment.writePaths.isNotEmpty()) {
            appendLine("- writePaths：${assignment.writePaths.joinToString(", ")}")
        }
        appendLine("- task：${truncate(assignment.task.replace('\n', ' '), SESSION_TEXT_CHARS)}")
        assignment.summary?.takeIf { it.isNotBlank() }?.let {
            appendLine("- summary：${truncate(it.replace('\n', ' '), TOOL_OUTPUT_CHARS)}")
        }
        assignment.error?.takeIf { it.isNotBlank() }?.let {
            appendLine("- error：${truncate(it.replace('\n', ' '), TOOL_OUTPUT_CHARS)}")
        }
    }

    private fun renderGroupMessage(message: GroupMessage): String {
        val flags = buildList {
            if (message.isStreaming) add("streaming")
            message.delegatedTaskId?.let { add("delegatedTask=$it") }
            if (message.attachments.isNotEmpty()) {
                add("attachments=${message.attachments.joinToString("|") { it.name }}")
            }
            if (message.reactions.isNotEmpty()) {
                add("reactions=${message.reactions.groupingBy { it.emoji }.eachCount()}")
            }
            if (message.steps.isNotEmpty()) {
                add("steps=${message.steps.size}")
            }
        }.joinToString(", ")
        val head = "[${stamp(message.timestamp)}] ${message.senderType} ${message.senderName} (${message.senderId})" +
            if (flags.isNotBlank()) " · $flags" else ""
        val reasoning = message.reasoning.takeIf { it.isNotBlank() }
            ?.let { "\n推理：${truncate(it, SESSION_REASONING_CHARS)}" }.orEmpty()
        return "$head\n${truncate(message.content, GROUP_MESSAGE_CHARS).ifBlank { "（空）" }}$reasoning\n"
    }

    private fun renderSessionMessage(message: HarnessMessage): String {
        val head = "[${stamp(message.createdAt)}]"
        return when (message) {
            is UserMessage -> "$head 用户：${truncate(message.text, SESSION_TEXT_CHARS)}\n"
            is AssistantText -> buildString {
                appendLine("$head 助手：${truncate(message.text, SESSION_TEXT_CHARS)}")
                message.reasoning?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  推理：${truncate(it.replace('\n', ' '), SESSION_REASONING_CHARS)}")
                }
                val meta = listOfNotNull(
                    message.modelId?.let { "model=$it" },
                    message.totalMs?.let { "totalMs=$it" },
                    message.promptTokens?.let { "prompt=$it" },
                    message.completionTokens?.let { "completion=$it" },
                ).joinToString(", ")
                if (meta.isNotBlank()) appendLine("  ($meta)")
            }
            is ToolCall -> "$head 工具调用：${message.rawToolName ?: message.tool.name} " +
                "${truncate(message.args.toString(), TOOL_ARGS_CHARS)}\n"
            is ToolResult -> buildString {
                appendLine("$head 工具结果：${if (message.success) "成功" else "失败"}" +
                    (message.errorCode?.let { " · $it" } ?: "") +
                    (message.durationMs?.let { " · ${it}ms" } ?: ""))
                appendLine("  ${truncate(message.output.replace("\n", "\n  "), TOOL_OUTPUT_CHARS)}")
            }
            else -> "$head ${message::class.simpleName} (id=${message.id})\n"
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n…（已截断）"

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val PERSONA_CHARS = 800
        const val GROUP_MESSAGE_CHARS = 4_000
        const val SESSION_MESSAGE_LIMIT = 200
        const val RUNTIME_ENTRY_LIMIT = 400
        const val RUNTIME_PAYLOAD_CHARS = 2_000
        const val SESSION_TEXT_CHARS = 4_000
        const val SESSION_REASONING_CHARS = 1_000
        const val TOOL_ARGS_CHARS = 1_000
        const val TOOL_OUTPUT_CHARS = 3_000
        const val MAX_LOG_CHARS = 300_000
        const val FILTERED_LOG_CHARS = 120_000
    }
}
