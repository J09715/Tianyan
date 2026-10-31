package top.tianyan.app.harness.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.harness.bot.BotModelManager

/** All members can inspect saved models and change only their own binding. Identity comes from storage. */
internal fun botModelTools(bots: PersonaBotRepository, manager: BotModelManager?): List<PluginTool> = listOf(
    PluginTool(
        id = "list-models",
        description = "分页查看已添加供应商；传 profile_id 分页查看该档案的全部具体模型。所有成员可用，不返回凭据。",
        schema = pluginObjectSchema(emptyList(), mapOf(
            "profile_id" to pluginStringProperty("可选供应商档案 ID；省略列供应商，填写则列该档案的模型"),
            "offset" to pluginIntegerProperty("分页起点，默认 0", 0, 100_000),
            "limit" to pluginIntegerProperty("本页最多条数，默认 20", 1, 50),
        )),
    ) { request ->
        val bot = bots.getBotBySessionId(request.sessionId)
            ?: return@PluginTool PluginToolOutput(false, "当前会话不属于团队成员")
        val service = manager ?: return@PluginTool PluginToolOutput(false, "模型管理器未初始化")
        val rows = service.catalog()
        val profileId = request.arguments["profile_id"]?.jsonPrimitive?.contentOrNull
        val profile = profileId?.let { id -> rows.firstOrNull { it.id == id } }
        if (profileId != null && profile == null) return@PluginTool PluginToolOutput(false, "供应商档案不存在")
        val offset = (request.arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 100_000)
        val limit = (request.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)
        val lines = if (profile != null) {
            profile.model.split(',').map(String::trim).filter(String::isNotBlank).distinct().map { "model=$it" }
        } else rows.map { item ->
            val variants = item.model.split(',').map(String::trim).filter(String::isNotBlank)
            "profile_id=${item.id} · ${item.name} · provider=${item.provider} · models=${variants.take(5).joinToString()} · model_count=${variants.size}"
        }
        PluginToolOutput(true, buildString {
            appendLine("当前成员：${bot.name}；profile_id=${bot.modelId ?: "默认"}；model=${bot.modelVariant ?: "档案默认"}")
            appendLine(if (bot.allowModelSelection) "允许自行切换模型；修改影响自己的私聊、所有群聊与独立任务。" else "模型已锁定；仅用户可在成员详情中允许自行切换。")
            if (profile != null) appendLine("供应商档案：${profile.id} · ${profile.name} · ${profile.provider}")
            var consumed = 0
            for (line in lines.drop(offset).take(limit)) {
                if (length + line.length > BOT_TOOL_MAX_CHARS - 250) break
                appendLine(line)
                consumed++
            }
            appendLine("offset=$offset · total=${lines.size}")
            if (offset + consumed < lines.size) {
                appendLine(if (consumed == 0) "当前条目过长，无法完整展示；请在成员设置查看" else "next_offset=${offset + consumed}")
            }
            if (profile == null) appendLine("传 profile_id 查看该供应商的全部模型；超过一页时传 next_offset 继续。")
        })
    },
    PluginTool(
        id = "select-model",
        description = "仅在用户开启允许自行切换后，为自己选择模型，下一次请求生效；影响自己的私聊、所有群聊和独立任务。省略 profile_id 跟随全局默认。不改变其他成员或全局激活模型。",
        schema = pluginObjectSchema(emptyList(), mapOf(
            "profile_id" to pluginStringProperty("list-models 返回的供应商档案 ID；省略恢复默认"),
            "model" to pluginStringProperty("该档案下已添加的具体模型名；省略使用档案默认模型"),
        )),
    ) { request ->
        val bot = bots.getBotBySessionId(request.sessionId)
            ?: return@PluginTool PluginToolOutput(false, "当前会话不属于团队成员")
        val service = manager ?: return@PluginTool PluginToolOutput(false, "模型管理器未初始化")
        try {
            val selected = service.select(bot.id, request.arguments["profile_id"]?.jsonPrimitive?.contentOrNull,
                request.arguments["model"]?.jsonPrimitive?.contentOrNull)
            PluginToolOutput(true, "已为 ${selected.name} 选择 ${selected.modelId ?: "默认"} / ${selected.modelVariant ?: "档案默认"}；下一次模型请求生效")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PluginToolOutput(false, "模型选择失败：${failure.message}")
        }
    },
)
