package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.harness.bot.BotAssignmentRuntime

internal fun independentAssignmentTools(
    bots: PersonaBotRepository,
    groups: GroupChatRepository,
    runtime: (() -> BotAssignmentRuntime)?,
): List<PluginTool> = listOf(
    PluginTool(
        id = "continue-assignment",
        description = "向原任务的独立会话补充要求或继续执行；运行中只排队，不打断当前工具。补充要求会重新打开所属工作批次，全部结束后再次统一回报。保留任务 ID、执行环境和历史。仅原派发负责人可用。",
        schema = pluginObjectSchema(listOf("assignment_id", "instruction"), mapOf(
            "assignment_id" to pluginStringProperty("原任务 ID"),
            "instruction" to pluginStringProperty("完整补充要求；受阻时提供缺失信息，结果待核对时先要求只读核验"),
            "request_id" to pluginStringProperty("可选幂等 ID；重试同一要求时复用，内容不得改变"),
        )),
    ) { request ->
        val caller = bots.getBotBySessionId(request.sessionId) ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
        val engine = runtime?.invoke() ?: return@PluginTool PluginToolOutput(false, "任务执行器尚未初始化")
        val id = request.arguments["assignment_id"]?.jsonPrimitive?.content.orEmpty()
        val task = engine.taskForId(id) ?: return@PluginTool PluginToolOutput(false, "任务不存在")
        if (!assignmentInSessionGroup(request.sessionId, task, bots) || !canManageAssignment(caller, task, groups)) return@PluginTool PluginToolOutput(false, DIRECT_REPORT_ONLY)
        val groupId = task.groupId ?: return@PluginTool PluginToolOutput(false, "任务没有群聊来源")
        if (resolveGroup(caller, groupId, groups) == null) return@PluginTool PluginToolOutput(false, "你不是该群成员")
        val instruction = request.arguments["instruction"]?.jsonPrimitive?.content.orEmpty()
        val key = request.arguments["request_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val receipt = if (key == null) engine.continueTask(id, groupId, instruction) else engine.continueTask(id, groupId, instruction, key)
        val batchNote = task.batchId?.let { "；同批任务全部结束后统一回报" }.orEmpty()
        PluginToolOutput(true, "补充要求已持久受理（任务 $id，要求 $receipt$batchNote）；在原会话按顺序执行。")
    },
    PluginTool(
        id = "report-assignment",
        description = "当前独立任务记录真实受阻原因与产物路径。普通偏好应自行选择推荐项继续；不能把计划或交接当完成。该工具不会立即结束正在执行的轮次。",
        schema = pluginObjectSchema(emptyList(), mapOf(
            "blocked_reason" to pluginStringProperty("仅真实无法继续时填写；省略可清除本轮受阻标记"),
            "artifact_paths" to pluginStringArrayProperty("本轮实际产物路径，仅作报告，不代替验收", "文件路径"),
        )),
    ) { request ->
        val engine = runtime?.invoke() ?: return@PluginTool PluginToolOutput(false, "任务执行器尚未初始化")
        val task = engine.taskForSession(request.sessionId) ?: return@PluginTool PluginToolOutput(false, "仅独立任务会话可记录自身报告")
        val groupId = task.groupId
        if (groupId == null || groups.getMembers(groupId).none { it.id == task.botId }) {
            return@PluginTool PluginToolOutput(false, "当前执行成员已不在来源群聊中")
        }
        val reason = request.arguments["blocked_reason"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(4_000)
        val saved = engine.report(request.sessionId, reason, request.stringArray("artifact_paths").take(20))
        PluginToolOutput(saved, if (saved) "已记录本轮报告；请输出真实结果，轮次结束后由原派发负责人验收。" else "本轮已结束或已取消，未修改报告")
    },
)
