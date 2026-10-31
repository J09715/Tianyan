package top.tianyan.app.harness.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.groupchat.GroupChatDispatcher

/**
 * 主控 Bot 的团队管理工具：孵化新 Bot、向指定 Bot 派发后台子任务、查看名册。
 *
 * 团队生命周期仅主控可用；派单限群内直属下级；模型目录与自身模型选择向所有成员开放。身份由会话反查。
 */
fun botManagementPlugin(
    botRepository: PersonaBotRepository,
    groupRepository: GroupChatRepository,
    orchestrator: () -> BotOrchestrator,
    dispatcher: () -> GroupChatDispatcher,
    assignments: BotAssignmentTracker,
    logger: AppLogger,
    runtime: (() -> top.tianyan.app.harness.bot.BotAssignmentRuntime)? = null,
    modelManager: top.tianyan.app.harness.bot.BotModelManager? = null,
    /** 各会话运行态快照（list-bots 状态行用）；缺省空表时状态行退化为任务维度。 */
    sessionStates: () -> Map<String, top.tianyan.app.core.model.SessionRunState> = { emptyMap() },
): HarnessPlugin {
    val descriptor = botManagementDescriptor()
    val createSchema = pluginObjectSchema(
        required = listOf("name", "role", "system_prompt"),
        properties = mapOf(
            "name" to pluginStringProperty("新 Bot 的名称（团队内建议唯一）"),
            "role" to pluginStringProperty("一句话职责，如「后端工程师」"),
            "system_prompt" to pluginStringProperty("完整人格与工作约束，会写入 persona.md"),
            "model_id" to pluginStringProperty("可选模型 ID；省略则使用默认模型"),
            "model" to pluginStringProperty("该供应商档案下已添加的具体模型名；省略使用档案默认"),
            "color_hex" to pluginStringProperty("可选主题色，如 #3D5AFE"),
            "group_id" to pluginStringProperty("可选群聊 ID；省略时加入你所在的第一个群聊"),
        ),
    )
    val assignSchema = pluginObjectSchema(
        required = listOf("bot_name", "task"),
        properties = mapOf(
            "bot_name" to pluginStringProperty("目标 Bot 名称"),
            "proposal_id" to pluginStringProperty("审议方案ID；审核和执行须绑定方案。下级执行继承原方案"),
            "work_item_id" to pluginStringProperty("同阶段工作项的稳定ID；重试须复用，省略按执行成员去重。拆分互不冲突的交付物时分别提供ID、写入范围与验收标准"),
            "task" to pluginStringProperty("要交给它独立完成的具体任务"),
            "write_paths" to pluginStringArrayProperty(
                "可选建议写入目录；群聊独立任务作为提示，无群聊子任务作为写入租约",
                "建议写入的沙箱路径",
            ),
            "group_id" to pluginStringProperty("可选群聊 ID；省略时使用主控 Bot 所在的第一个群"),
            "wait" to pluginBooleanProperty(
                "是否等待独立任务本轮结束并返回；省略时有群聊则立即返回并归入本轮工作批次，全部任务结束后统一回报；wait=true 不进入批次",
            ),
            "depends_on" to pluginStringArrayProperty(
                "可选前置任务 ID 列表；全部 COMPLETED 后才允许派发，用于串行依赖",
                "assign-task 返回的任务 ID",
            ),
        ),
    )
    val listSchema = pluginObjectSchema(emptyList(), mapOf("group_id" to pluginStringProperty("来源群聊 ID；仅列直属下级")))
    val assignmentListSchema = pluginObjectSchema(
        emptyList(),
        mapOf("group_id" to pluginStringProperty("可选群聊 ID；省略时列出最近 20 条")),
    )
    val assignmentIdSchema = pluginObjectSchema(
        required = listOf("assignment_id"),
        properties = mapOf("assignment_id" to pluginStringProperty("assign-task 返回的任务 ID")),
    )
    return HarnessPlugin(
        descriptor = descriptor,
        tools = listOf(
            PluginTool(
                id = "create-bot",
                description = "创建一名具有独立人格、独占会话与 Git 记忆的 Bot。仅主控 Bot 可用。",
                schema = createSchema,
            ) { request ->
                val caller = requireMainBot(request.sessionId, botRepository)
                    ?: return@PluginTool PluginToolOutput(false, MAIN_BOT_ONLY)
                val name = request.arguments["name"]?.jsonPrimitive?.content.orEmpty()
                val role = request.arguments["role"]?.jsonPrimitive?.content.orEmpty()
                val prompt = request.arguments["system_prompt"]?.jsonPrimitive?.content.orEmpty()
                val modelId = request.arguments["model_id"]?.jsonPrimitive?.content
                val color = request.arguments["color_hex"]?.jsonPrimitive?.content
                val group = resolveGroup(
                    caller,
                    request.arguments["group_id"]?.jsonPrimitive?.content,
                    groupRepository,
                )
                if (!request.arguments["group_id"]?.jsonPrimitive?.content.isNullOrBlank() && group == null) {
                    return@PluginTool PluginToolOutput(false, "找不到群聊或你不是群成员")
                }
                runCatching {
                    orchestrator().createBot(
                        name = name,
                        role = role,
                        systemPrompt = prompt,
                        modelId = modelId,
                        modelVariant = request.arguments["model"]?.jsonPrimitive?.content,
                        colorHex = normalizeColor(color),
                        groupId = group?.id,
                    )
                }.fold(
                    onSuccess = { bot ->
                        PluginToolOutput(
                            true,
                            "✅ 已孵化 ${bot.name}（${bot.role}）${group?.let { "，已加入群聊「${it.name}」" } ?: ""}",
                        )
                    },
                    onFailure = { PluginToolOutput(false, "创建 Bot 失败：${it.message}") },
                )
            },
            PluginTool(
                id = "assign-task",
                description = "把任务交给指定 Bot 在独立会话执行：成员仍在群聊实时输出与展示工具步骤，任务可继续。同一轮 wait=false 的任务归入同一工作批次，全部结束后统一回报。可派给直属下级或审议规则授权的协作成员；审核与执行传 proposal_id。",
                schema = assignSchema,
            ) { request ->
                val caller = botRepository.getBotBySessionId(request.sessionId)
                    ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
                val targetName = request.arguments["bot_name"]?.jsonPrimitive?.content.orEmpty()
                val task = request.arguments["task"]?.jsonPrimitive?.content.orEmpty()
                if (task.isBlank()) return@PluginTool PluginToolOutput(false, "任务内容不能为空")
                val dependsOn = request.stringArray("depends_on")
                val unmet = unmetDependencies(assignments, dependsOn)
                if (unmet.isNotEmpty()) {
                    return@PluginTool PluginToolOutput(
                        false,
                        "前置任务尚未完成：${unmet.joinToString("；")}。请先用 list-assignments / inspect-assignment 确认状态为 COMPLETED 后再派发。",
                    )
                }
                val writePaths = request.stringArray("write_paths")
                val requestedGroup = request.arguments["group_id"]?.jsonPrimitive?.content
                val group = resolveTeamGroup(caller, request.sessionId, requestedGroup, groupRepository, botRepository)
                if (group == null && (!requestedGroup.isNullOrBlank() || !caller.isMainBot)) {
                    return@PluginTool PluginToolOutput(false, "找不到来源群聊或当前会话不能管理该群")
                }
                val target = (if (group == null) botRepository.findBotByName(targetName) else groupRepository.getMembers(group.id).singleOrNull { it.name == targetName })
                    ?: return@PluginTool PluginToolOutput(false, "找不到名为「$targetName」的 Bot")
                if (target.id == caller.id) {
                    return@PluginTool PluginToolOutput(false, "不能把任务派发给自己；请直接执行")
                }
                if (group != null && !groupRepository.getTeam(group.id).canAssign(caller.id, target.id) && !groupRepository.getGovernance(group.id).canRoute(caller.id, target.id)) {
                    return@PluginTool PluginToolOutput(false, DIRECT_REPORT_ONLY)
                }
                if (group != null && groupRepository.getMembers(group.id).none { it.id == target.id }) {
                    return@PluginTool PluginToolOutput(
                        false,
                        "${target.name} 不在群聊「${group.name}」中；请先把成员加入群聊再派发。",
                    )
                }
                if (group != null) {
                    val engine = runtime?.invoke() ?: return@PluginTool PluginToolOutput(false, "独立任务执行器尚未初始化")
                    val wait = request.arguments["wait"]?.jsonPrimitive?.booleanOrNull ?: false
                    val created = runCatching {
                        engine.create(
                            target.id, group.id, caller.id, request.sessionId, task, writePaths,
                            dependsOn = dependsOn, proposalId = request.arguments["proposal_id"]?.jsonPrimitive?.content,
                            workItemId = request.arguments["work_item_id"]?.jsonPrimitive?.content, operationId = request.operationId,
                            workId = if (wait) null else request.operationId ?: if (engine.taskForSession(request.sessionId) != null) java.util.UUID.randomUUID().toString() else null,
                        )
                    }.getOrElse { return@PluginTool PluginToolOutput(false, "派发失败：${it.message}") }
                    if (wait) {
                        val result = engine.awaitSynchronousTask(created.id, request.sessionId)
                        return@PluginTool PluginToolOutput(result.status == BotAssignmentStatus.COMPLETED,
                            "任务 ${created.id}：${result.summary ?: result.error ?: result.status}")
                    }
                    val batchNote = created.batchId?.let { batchId ->
                        val size = runCatching { engine.batchSize(batchId) }.getOrDefault(1)
                        "，已归入本轮工作批次（同批 $size 个任务，全部结束后统一回报）"
                    }.orEmpty()
                    return@PluginTool PluginToolOutput(true, "任务 ${created.id} · ${created.status} · 执行成员 ${target.name} · 原负责人 ${created.reportToBotId}$batchNote；同一工作项复用原任务，不重复执行、不覆盖原指令。补充或修改要求请用 continue-assignment 继续原任务。")
                }
                if (request.arguments["wait"]?.jsonPrimitive?.booleanOrNull == false) {
                    return@PluginTool PluginToolOutput(false, "独立后台委派需要群聊来源；请加入群聊或使用 wait=true 同步执行")
                }
                val assignment = newAssignment(target, null, task, writePaths, dependsOn = dependsOn)
                assignments.start(assignment, currentCoroutineContext()[Job])
                var status = BotAssignmentStatus.FAILED
                var summary: String? = null
                var error: String? = null
                try {
                    val result = orchestrator().dispatchSubtask(target.id, task, writePaths)
                    summary = result.summary
                    status = if (result.success) BotAssignmentStatus.COMPLETED else BotAssignmentStatus.FAILED
                    PluginToolOutput(result.success, "任务 ${assignment.id}：${result.summary}")
                } catch (cancelled: CancellationException) {
                    status = BotAssignmentStatus.CANCELLED
                    error = "用户取消"
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message
                    PluginToolOutput(false, "执行失败：$error")
                } finally {
                    withContext(NonCancellable) { assignments.finish(assignment.id, status, summary, error) }
                }
            },
            PluginTool(
                id = "list-bots",
                description = "查看当前团队的直属下级名册。",
                schema = listSchema,
            ) { request ->
                val caller = botRepository.getBotBySessionId(request.sessionId)
                    ?: return@PluginTool PluginToolOutput(false, "无法确认当前 Bot 身份")
                val group = resolveTeamGroup(caller, request.sessionId,
                    request.arguments["group_id"]?.jsonPrimitive?.content, groupRepository, botRepository)
                if (group == null && !request.arguments["group_id"]?.jsonPrimitive?.content.isNullOrBlank()) return@PluginTool PluginToolOutput(false, "找不到来源群聊")
                val reports = group?.let { groupRepository.getTeam(it.id).directReports(caller.id) + groupRepository.getMembers(it.id).filter { bot -> groupRepository.getGovernance(it.id).canRoute(caller.id, bot.id) }.map { bot -> bot.id } }.orEmpty()
                if (group == null && !caller.isMainBot || group != null && caller.id != group.adminBotId && reports.isEmpty()) return@PluginTool PluginToolOutput(false, DIRECT_REPORT_ONLY)
                val bots = if (group != null) groupRepository.getMembers(group.id).filter { it.id in reports }
                    else botRepository.listBots()
                if (bots.isEmpty()) {
                    PluginToolOutput(true, "（暂无 Bot）")
                } else {
                    val states = sessionStates()
                    val activeBots = assignments.active.value.map { it.botName }.toSet()
                    // groupSessionId 是挂起调用，先取齐再进非挂起的 joinToString；
                    // 状态行是可选增强，映射查询失败不阻断名册输出。
                    val groupSessionIds = group?.let { source ->
                        bots.associate { bot ->
                            bot.id to runCatching { orchestrator().groupSessionId(bot.id, source.id) }.getOrNull()
                        }
                    }.orEmpty()
                    val text = bots.joinToString("\n") { bot ->
                        val mark = if (bot.isMainBot) "👑 主控" else "成员"
                        val sessionIds = buildList {
                            add(bot.sessionId)
                            groupSessionIds[bot.id]?.let(::add)
                        }
                        "- bot_name=\"${bot.name}\" · ${botStatusLine(states, sessionIds, activeBots, bot.name)}" +
                            " · 职责=${bot.role} · $mark · 模型 ${bot.modelId ?: "默认"} / ${bot.modelVariant ?: "档案默认"}"
                    }
                    PluginToolOutput(true, text.take(BOT_TOOL_MAX_CHARS))
                }
            },
        ) + botAssignmentTools(
            botRepository = botRepository,
            groupRepository = groupRepository,
            assignments = assignments, runtime = runtime,
        ) + independentAssignmentTools(botRepository, groupRepository, runtime) + botModelTools(botRepository, modelManager) + groupReviewTools(botRepository, groupRepository, runtime),
    )
}

internal suspend fun requireMainBot(
    sessionId: String,
    botRepository: PersonaBotRepository,
): Bot? = botRepository.getBotBySessionId(sessionId)?.takeIf { it.isMainBot }

internal const val MAIN_BOT_ONLY = "只有主控 Bot 可以管理团队"
internal const val BOT_TOOL_MAX_CHARS = 12_000
internal const val BOT_BACKGROUND_SUMMARY_CHARS = 4_000
