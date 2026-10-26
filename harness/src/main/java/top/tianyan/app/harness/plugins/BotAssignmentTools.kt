package top.tianyan.app.harness.plugins

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentRequest
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotWorkBatch
import top.tianyan.app.core.model.bot.BotWorkBatchStatus
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.groupchat.GroupTurnOutcome

internal fun PluginToolRequest.stringArray(name: String): List<String> =
    (arguments[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        ?.filter { it.isNotBlank() }
        .orEmpty()

internal fun newAssignment(
    target: Bot,
    group: GroupChat?,
    task: String,
    writePaths: List<String>,
    sessionId: String? = null,
    now: Long = System.currentTimeMillis(),
    dependsOn: List<String> = emptyList(),
): BotAssignment {
    val taskId = UUID.randomUUID().toString()
    return BotAssignment(
        id = taskId,
        groupId = group?.id,
        botId = target.id,
        botName = target.name,
        title = task.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .take(60)
            .ifBlank { "未命名任务" },
        task = task,
        status = BotAssignmentStatus.RUNNING,
        writePaths = writePaths,
        createdAt = now,
        startedAt = now,
        sessionId = sessionId ?: target.sessionId,
        laneName = null,
        dependsOn = dependsOn,
    )
}

internal suspend fun unmetDependencies(
    assignments: BotAssignmentTracker,
    dependsOn: List<String>,
): List<String> = dependsOn.distinct().mapNotNull { id ->
    val dependency = assignments.get(id)
    when {
        dependency == null -> "$id（不存在）"
        dependency.status != BotAssignmentStatus.COMPLETED -> "$id（${statusLabel(dependency.status)}）"
        else -> null
    }
}

internal fun formatAssignmentReport(
    assignment: BotAssignment,
    result: GroupTurnOutcome,
): String {
    val mark = if (result.failed) "⚠️" else "✅"
    val status = if (result.failed) "执行未完成" else "已在群聊完成主控委派"
    val truncated = if (result.text.length > BOT_BACKGROUND_SUMMARY_CHARS) "\n\n…（结果已截断）" else ""
    return "$mark **${assignment.botName}** $status（任务 ${assignment.id}）\n\n" +
        result.text.take(BOT_BACKGROUND_SUMMARY_CHARS) + truncated
}

internal fun botAssignmentTools(
    botRepository: PersonaBotRepository,
    groupRepository: GroupChatRepository,
    assignments: BotAssignmentTracker,
    runtime: (() -> top.tianyan.app.harness.bot.BotAssignmentRuntime)? = null,
): List<PluginTool> {
    val listSchema = pluginObjectSchema(
        emptyList(),
        mapOf("group_id" to pluginStringProperty("可选群聊 ID；省略时列出最近 20 条委派")),
    )
    val idSchema = pluginObjectSchema(
        required = listOf("assignment_id"),
        properties = mapOf("assignment_id" to pluginStringProperty("assign-task 返回的任务 ID")),
    )
    return listOf(
        PluginTool(
            id = "list-assignments",
            description = "列出群聊中的委派任务（按工作批次分组，含执行中与历史），用于查看进度、验收结果和确认依赖前置状态。查看范围限当前团队；修改限原派发负责人。",
            schema = listSchema,
        ) { request ->
            val caller = botRepository.getBotBySessionId(request.sessionId)
                ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
            val requestedGroupId = request.arguments["group_id"]?.jsonPrimitive?.content
            val rows = (if (requestedGroupId.isNullOrBlank()) {
                assignments.listRecent(20)
            } else {
                val group = resolveGroup(caller, requestedGroupId, groupRepository)
                    ?: return@PluginTool PluginToolOutput(false, "找不到群聊或你不是群成员")
                assignments.listForGroup(group.id).take(30)
            }).filter { assignmentInSessionGroup(request.sessionId, it, botRepository) && canInspectAssignment(caller, it, groupRepository) }
            if (rows.isEmpty()) {
                PluginToolOutput(true, "（暂无委派任务）")
            } else {
                val batches = rows.mapNotNull { it.batchId }.distinct()
                    .mapNotNull { assignments.getBatch(it) }
                    .associateBy { it.id }
                PluginToolOutput(true, formatAssignmentRows(rows, batches).take(BOT_TOOL_MAX_CHARS))
            }
        },
        PluginTool(
            id = "inspect-assignment",
            description = "查看单个委派任务的完整记录：状态、耗时、写入租约、结果摘要与错误。查看范围限当前团队；修改限原派发负责人。",
            schema = idSchema,
        ) { request ->
            val caller = botRepository.getBotBySessionId(request.sessionId)
                ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
            val id = request.arguments["assignment_id"]?.jsonPrimitive?.content.orEmpty()
            val assignment = assignments.get(id)
                ?: return@PluginTool PluginToolOutput(false, "找不到任务「$id」")
            if (!assignmentInSessionGroup(request.sessionId, assignment, botRepository) || !canInspectAssignment(caller, assignment, groupRepository)) return@PluginTool PluginToolOutput(false, "只能查看自己负责的任务及下级任务")
            val history = runtime?.invoke()?.history(id).orEmpty().joinToString("\n\n") {
                val status = if (it.status == BotAssignmentRequest.RECEIVED) "已接收（待原任务验收）" else it.status
                "补充要求 ${it.id} · $status\n${it.instruction.take(800)}\n${it.blockedReason ?: it.summary ?: it.error ?: ""}\n报告产物：${it.artifacts.joinToString()}"
            }
            val batchInfo = assignment.batchId?.let { batchId ->
                val batch = assignments.getBatch(batchId)
                val siblings = assignments.batchAssignments(batchId).filter {
                    assignmentInSessionGroup(request.sessionId, it, botRepository) && canInspectAssignment(caller, it, groupRepository)
                }
                buildString {
                    appendLine()
                    appendLine("【工作批次】")
                    appendLine("ID：$batchId")
                    appendLine(
                        "状态：${batch?.let { batchStatusLabel(it.status) } ?: "未知"} · 累计 ${batch?.revision ?: "-"} 次派发" +
                            (batch?.reportRevision?.let { " · 已汇总 revision $it" } ?: ""),
                    )
                    appendLine(
                        "同批：${siblings.size} 个任务；" +
                            "${siblings.count { it.status == BotAssignmentStatus.COMPLETED }} 完成 / " +
                            "${siblings.count { it.status.isActive }} 进行中 / " +
                            "${siblings.count { it.status in ATTENTION_STATUSES }} 需关注",
                    )
                    siblings.forEach { appendLine("  ${assignmentLine(it)}") }
                }
            }.orEmpty()
            val usage = runtime?.invoke()?.describeUsage(id).orEmpty()
            PluginToolOutput(true, (usage + "\n\n" + assignmentDetail(assignment) + batchInfo + "\n\n【轮次记录】\n" + history).take(BOT_TOOL_MAX_CHARS))
        },
        PluginTool(
            id = "cancel-assignment",
            description = "取消一个仍在执行的委派任务。查看范围限当前团队；修改限原派发负责人。",
            schema = idSchema,
        ) { request ->
            val caller = botRepository.getBotBySessionId(request.sessionId)
                ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
            val id = request.arguments["assignment_id"]?.jsonPrimitive?.content.orEmpty()
            val assignment = assignments.get(id)
                ?: return@PluginTool PluginToolOutput(false, "找不到任务「$id」")
            if (!assignmentInSessionGroup(request.sessionId, assignment, botRepository) || !canManageAssignment(caller, assignment, groupRepository)) return@PluginTool PluginToolOutput(false, DIRECT_REPORT_ONLY)
            if (!assignment.status.isActive) {
                return@PluginTool PluginToolOutput(
                    false,
                    "任务已结束（${statusLabel(assignment.status)}），无需取消",
                )
            }
            assignments.cancel(id)
            PluginToolOutput(
                true,
                "🛑 已取消 ${assignment.botName} 的任务「${assignment.title}」（${assignment.id}）",
            )
        },
    )
}

internal fun statusLabel(status: BotAssignmentStatus): String = when (status) {
    BotAssignmentStatus.QUEUED -> "排队中"
    BotAssignmentStatus.WAITING_CHILDREN -> "等待协作交付"
    BotAssignmentStatus.BLOCKED -> "受阻"
    BotAssignmentStatus.INTERRUPTED -> "结果待核对"
    BotAssignmentStatus.RUNNING -> "执行中"
    BotAssignmentStatus.COMPLETED -> "已完成"
    BotAssignmentStatus.FAILED -> "失败"
    BotAssignmentStatus.CANCELLED -> "已取消"
}

private fun formatAssignmentRows(rows: List<BotAssignment>, batches: Map<String, BotWorkBatch>): String {
    val batched = rows.filter { it.batchId != null }.groupBy { it.batchId!! }
    val loose = rows.filter { it.batchId == null }
    val out = mutableListOf<String>()
    batched.forEach { (batchId, tasks) ->
        val batch = batches[batchId]
        val done = tasks.count { it.status == BotAssignmentStatus.COMPLETED }
        val running = tasks.count { it.status.isActive }
        val attention = tasks.count { it.status in ATTENTION_STATUSES }
        out += "工作批次 ${batchId.take(8)} · ${batch?.let { batchStatusLabel(it.status) } ?: "未知"} · " +
            "累计 ${batch?.revision ?: 1} 次派发（$done 完成 / $running 进行中 / $attention 需关注）"
        tasks.forEach { out += "  ${assignmentLine(it)}" }
    }
    if (loose.isNotEmpty()) {
        if (out.isNotEmpty()) out += ""
        out += "未归批任务"
        loose.forEach { out += "  ${assignmentLine(it)}" }
    }
    return out.joinToString("\n")
}

private val ATTENTION_STATUSES = setOf(
    BotAssignmentStatus.BLOCKED,
    BotAssignmentStatus.INTERRUPTED,
    BotAssignmentStatus.FAILED,
)

private fun batchStatusLabel(status: BotWorkBatchStatus): String = when (status) {
    BotWorkBatchStatus.WAITING_CHILDREN -> "等待成员"
    BotWorkBatchStatus.WAITING_TURN -> "等待主控本轮结束"
    BotWorkBatchStatus.SETTLED -> "已汇总"
    BotWorkBatchStatus.CANCELLED -> "已取消"
}

private fun assignmentLine(assignment: BotAssignment): String {
    val timing = if (assignment.status == BotAssignmentStatus.RUNNING) {
        "已运行 ${formatDuration(System.currentTimeMillis() - assignment.startedAt)}"
    } else {
        assignment.finishedAt
            ?.let { "耗时 ${formatDuration(it - assignment.startedAt)}" }
            .orEmpty()
    }
    return "- [${statusLabel(assignment.status)}] ${assignment.id} · ${assignment.botName}" +
        "「${assignment.title}」${timing.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
}

private fun assignmentDetail(assignment: BotAssignment): String = buildString {
    appendLine("任务：${assignment.title}")
    appendLine("ID：${assignment.id}")
    appendLine("状态：${statusLabel(assignment.status)}")
    appendLine("Bot：${assignment.botName}（${assignment.botId}）")
    appendLine("群聊：${assignment.groupId ?: "（无群聊，同步委派）"}")
    appendLine("开始：${formatTime(assignment.startedAt)}")
    assignment.finishedAt?.let { appendLine("结束：${formatTime(it)}") }
    appendLine("会话：${assignment.sessionId ?: "未知"}")
    assignment.laneName?.let { appendLine("Lane：$it") }
    if (assignment.writePaths.isNotEmpty()) {
        appendLine("${if (assignment.independent) "建议写入范围" else "写入租约"}：${assignment.writePaths.joinToString(", ")}")
    }
    appendLine()
    appendLine("【任务内容】")
    appendLine(assignment.task)
    assignment.summary?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine("【结果摘要】")
        appendLine(it)
    }
    assignment.error?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine("【错误】")
        appendLine(it)
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3_600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3_600}时${(seconds % 3_600) / 60}分"
    }
}
