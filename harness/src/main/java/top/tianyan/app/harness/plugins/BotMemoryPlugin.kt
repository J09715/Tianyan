package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.harness.bot.memory.BotMemoryManager

/**
 * Bot 的 Git 记忆能力：上下文注入 + 读写工具。
 *
 * 上下文以插件背景资料形式进入系统提示（不授予权限）；写入只允许 Markdown，
 * 每次落盘自动形成一条 Git 提交，可在 Bot 详情页回滚。
 */
fun botMemoryPlugin(
    botRepository: PersonaBotRepository,
    memoryManager: BotMemoryManager,
): HarnessPlugin {
    val descriptor = botMemoryDescriptor()
    val readSchema = pluginObjectSchema(
        required = listOf("path"),
        properties = mapOf("path" to pluginStringProperty("相对记忆路径，如 persona.md 或 knowledge/api.md")),
    )
    val writeSchema = pluginObjectSchema(
        required = listOf("path", "content"),
        properties = mapOf(
            "path" to pluginStringProperty("相对记忆路径，仅允许 .md；不得以 .git 开头"),
            "content" to pluginStringProperty("要写入的完整 Markdown 内容"),
            "commit_message" to pluginStringProperty("Git 提交说明；省略时自动生成"),
        ),
    )
    val historySchema = pluginObjectSchema(
        required = emptyList(),
        properties = mapOf("limit" to pluginIntegerProperty("返回的提交数量，1-30", minimum = 1, maximum = 30)),
    )
    return HarnessPlugin(
        descriptor = descriptor,
        contexts = listOf(
            PluginContext("memory-context") { request ->
                val bot = botRepository.getBotBySessionId(request.sessionId) ?: return@PluginContext ""
                memoryManager.assembleContext(bot.id)
            },
        ),
        tools = listOf(
            PluginTool(
                id = "memory-read",
                description = "读取当前 Bot 的 Git 记忆文件（persona.md / preferences.md / dialogue_notes.md / knowledge/*.md）。",
                schema = readSchema,
            ) { request ->
                val bot = requireBot(request.sessionId, botRepository)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可使用记忆工具")
                val path = request.arguments["path"]?.jsonPrimitive?.content.orEmpty()
                runCatching { memoryManager.readMemory(bot.id, path) }
                    .fold(
                        onSuccess = { content ->
                            PluginToolOutput(true, content.ifBlank { "（文件为空或不存在）" }.take(MAX_TOOL_CHARS))
                        },
                        onFailure = { PluginToolOutput(false, "记忆读取失败：${it.message}") },
                    )
            },
            PluginTool(
                id = "memory-write",
                description = "写入并提交一条 Bot 长效记忆。用于沉淀用户偏好、项目事实与工作笔记；只允许 Markdown。",
                schema = writeSchema,
            ) { request ->
                val bot = requireBot(request.sessionId, botRepository)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可使用记忆工具")
                val path = request.arguments["path"]?.jsonPrimitive?.content.orEmpty()
                val content = request.arguments["content"]?.jsonPrimitive?.content.orEmpty()
                val message = request.arguments["commit_message"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() } ?: "memory: 更新 $path"
                runCatching { memoryManager.writeMemory(bot.id, path, content, message.take(120)) }
                    .fold(
                        onSuccess = { commit ->
                            PluginToolOutput(true, "✅ 已提交 $path${commit?.let { " (${it.shortHash})" } ?: "（内容未变化）"}")
                        },
                        onFailure = { PluginToolOutput(false, "记忆写入失败：${it.message}") },
                    )
            },
            PluginTool(
                id = "memory-list",
                description = "列出当前 Bot 记忆仓库中的文件与大小。",
                schema = pluginObjectSchema(emptyList(), emptyMap()),
            ) { request ->
                val bot = requireBot(request.sessionId, botRepository)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可使用记忆工具")
                val files = memoryManager.listFiles(bot.id)
                val text = if (files.isEmpty()) "（记忆仓库为空）" else files.joinToString("\n") { "${it.path} · ${it.sizeBytes}B" }
                PluginToolOutput(true, text.take(MAX_TOOL_CHARS))
            },
            PluginTool(
                id = "memory-history",
                description = "查看当前 Bot 记忆仓库的提交时间线。",
                schema = historySchema,
            ) { request ->
                val bot = requireBot(request.sessionId, botRepository)
                    ?: return@PluginTool PluginToolOutput(false, "仅 Bot 会话可使用记忆工具")
                val limit = request.arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 30) ?: 10
                val commits = memoryManager.getCommitHistory(bot.id, limit)
                val text = if (commits.isEmpty()) "（暂无提交）" else commits.joinToString("\n") {
                    "${it.shortHash} · ${it.message} · ${it.author}"
                }
                PluginToolOutput(true, text.take(MAX_TOOL_CHARS))
            },
        ),
    )
}

private suspend fun requireBot(
    sessionId: String,
    botRepository: PersonaBotRepository,
) = botRepository.getBotBySessionId(sessionId)

private const val MAX_TOOL_CHARS = 24_000
