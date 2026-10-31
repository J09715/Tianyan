package top.tianyan.app.harness.plugins

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.BotSchedule
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup
import top.tianyan.app.harness.bot.schedule.CronSchedule

/**
 * 定时唤醒工具：把 5 段 Cron 指令写成 BotSchedule，由宿主 WorkManager 触发。
 *
 * 任何 Bot 都可以为自己排期；操作其他 Bot 的排期需要主控身份。
 */
fun botSchedulePlugin(
    botRepository: PersonaBotRepository,
    groupRepository: GroupChatRepository,
    scheduleRepository: BotScheduleRepository,
    wakeup: () -> BotScheduleWakeup?,
    logger: AppLogger,
): HarnessPlugin {
    val descriptor = botScheduleDescriptor()
    val scheduleSchema = pluginObjectSchema(
        required = listOf("cron", "instruction"),
        properties = mapOf(
            "cron" to pluginStringProperty("5 段 Cron：分 时 日 月 周，如 0 9 * * 1-5"),
            "instruction" to pluginStringProperty("到点后要执行的完整指令"),
            "bot_name" to pluginStringProperty("目标 Bot 名称；省略时为当前 Bot"),
            "group_id" to pluginStringProperty("可选群聊 ID；省略时回传到目标 Bot 所在群"),
        ),
    )
    val listSchema = pluginObjectSchema(
        emptyList(),
        properties = mapOf(
            "all" to pluginBooleanProperty("主控 Bot 设为 true 可查看所有 Bot 的排期"),
        ),
    )
    val cancelSchema = pluginObjectSchema(
        required = listOf("schedule_id"),
        properties = mapOf("schedule_id" to pluginStringProperty("排期 ID，可从 list-schedules 获取")),
    )
    return HarnessPlugin(
        descriptor = descriptor,
        tools = listOf(
            PluginTool(
                id = "schedule-task",
                description = "为 Bot 创建周期任务（标准 5 段 Cron）。到点后 Bot 会在群聊中主动执行并汇报。",
                schema = scheduleSchema,
            ) { request ->
                val caller = botRepository.getBotBySessionId(request.sessionId)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可以创建定时任务")
                val cron = request.arguments["cron"]?.jsonPrimitive?.content.orEmpty().trim()
                val instruction = request.arguments["instruction"]?.jsonPrimitive?.content.orEmpty().trim()
                if (instruction.isBlank()) return@PluginTool PluginToolOutput(false, "定时指令不能为空")
                val cronError = CronSchedule.validate(cron)
                if (cronError != null) return@PluginTool PluginToolOutput(false, "Cron 无效：$cronError")
                val targetName = request.arguments["bot_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val target = if (targetName == null || targetName.equals(caller.name, ignoreCase = true)) {
                    caller
                } else {
                    if (!caller.isMainBot) return@PluginTool PluginToolOutput(false, "只有主控 Bot 可以为其他 Bot 排期")
                    botRepository.findBotByName(targetName)
                        ?: return@PluginTool PluginToolOutput(false, "找不到名为「$targetName」的 Bot")
                }
                val requestedGroupId = request.arguments["group_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val groupId = if (requestedGroupId == null) {
                    null
                } else {
                    val group = groupRepository.getGroup(requestedGroupId)
                        ?: return@PluginTool PluginToolOutput(false, "找不到群聊「$requestedGroupId」")
                    if (groupRepository.getMembers(group.id).none { it.id == target.id }) {
                        return@PluginTool PluginToolOutput(false, "${target.name} 不在群聊「${group.name}」中")
                    }
                    group.id
                }
                val now = System.currentTimeMillis()
                val nextRunAt = CronSchedule.parse(cron).nextAfter(now)
                    ?: return@PluginTool PluginToolOutput(false, "该 Cron 在两年内没有可触发时间")
                val schedule = BotSchedule(
                    id = UUID.randomUUID().toString(),
                    botId = target.id,
                    groupId = groupId,
                    cron = cron,
                    instruction = instruction,
                    enabled = true,
                    nextRunAt = nextRunAt,
                    createdAt = now,
                )
                scheduleRepository.saveSchedule(schedule)
                runCatching { wakeup()?.request() }
                    .onFailure { logger.w("[BotSchedulePlugin] 重新规划唤醒失败: ${it.message}", it) }
                PluginToolOutput(
                    true,
                    "⏰ 已为 ${target.name} 排期（${schedule.id.take(8)}）：$cron\n下次触发：${formatTime(nextRunAt)}",
                )
            },
            PluginTool(
                id = "list-schedules",
                description = "查看 Bot 的周期任务及下次触发时间。",
                schema = listSchema,
            ) { request ->
                val caller = botRepository.getBotBySessionId(request.sessionId)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可以查看排期")
                val all = request.arguments["all"]?.jsonPrimitive?.booleanOrNull == true && caller.isMainBot
                val bots = if (all) botRepository.listBots() else listOf(caller)
                val lines = buildList {
                    bots.forEach { bot ->
                        val schedules = scheduleRepository.observeForBot(bot.id).first()
                        schedules.forEach { schedule ->
                            add(
                                "- ${schedule.id.take(8)} · ${bot.name} · ${schedule.cron} · " +
                                    "下次 ${formatTime(schedule.nextRunAt)} · ${schedule.instruction.take(60)}",
                            )
                        }
                    }
                }
                PluginToolOutput(true, lines.joinToString("\n").ifBlank { "（暂无定时任务）" }.take(MAX_TOOL_CHARS))
            },
            PluginTool(
                id = "cancel-schedule",
                description = "取消一条定时任务。",
                schema = cancelSchema,
            ) { request ->
                val caller = botRepository.getBotBySessionId(request.sessionId)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可以取消排期")
                val rawId = request.arguments["schedule_id"]?.jsonPrimitive?.content.orEmpty().trim()
                val schedule = findSchedule(rawId, caller, botRepository, scheduleRepository)
                    ?: return@PluginTool PluginToolOutput(false, "找不到排期「$rawId」")
                if (schedule.botId != caller.id && !caller.isMainBot) {
                    return@PluginTool PluginToolOutput(false, "只有主控 Bot 可以取消其他 Bot 的排期")
                }
                scheduleRepository.deleteSchedule(schedule.id)
                runCatching { wakeup()?.request() }
                PluginToolOutput(true, "🗑️ 已取消排期 ${schedule.id.take(8)}")
            },
        ),
    )
}

private fun formatTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

private suspend fun findSchedule(
    rawId: String,
    caller: top.tianyan.app.core.model.bot.Bot,
    botRepository: PersonaBotRepository,
    scheduleRepository: BotScheduleRepository,
): top.tianyan.app.core.model.bot.BotSchedule? {
    scheduleRepository.getSchedule(rawId)?.let { return it }
    if (rawId.length < 8) return null
    val scope = if (caller.isMainBot) botRepository.listBots() else listOf(caller)
    scope.forEach { bot ->
        scheduleRepository.observeForBot(bot.id).first()
            .firstOrNull { it.id.startsWith(rawId) }
            ?.let { return it }
    }
    return null
}

private const val MAX_TOOL_CHARS = 8_000
