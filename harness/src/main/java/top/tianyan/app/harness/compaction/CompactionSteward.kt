package top.tianyan.app.harness.compaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelConfig
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/** 单次判定调用端口：输入纯聊天请求，返回文本回答（失败由调用方兜底）。 */
fun interface StewardJudgeCaller {
    suspend fun call(model: ModelConfig, messages: List<ApiMessage>): String
}

/**
 * 上下文管家（借鉴 DS Bot steward）：压缩触发时判断「活是否告一段落」。
 *
 * - 没完 → 维持现状的窗口压缩（保留近期原文，继续当前工作）；
 * - 完了 → 换段：只保留最后一个用户轮起的原文，其余全部折叠为结构化交接摘要
 *   （天衍摘要层格式已含 目标/约束/进度/关键决策/下一步，即交接包）。
 *   被折叠的原文仍留在会话树里，可 history_search / history_read 回读。
 *
 * 判定本身是一次极小的纯聊天请求（无工具、maxTokens=64、只看近期约 6000 字符、
 * 只回一行 JSON）；失败一律回退为「未完成」，即退化为现状压缩。
 */
class CompactionSteward(
    private val caller: StewardJudgeCaller,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** 是否应换段（任务已告一段落）。任何失败/解析不出都返回 false。 */
    suspend fun shouldSwitchSegment(model: ModelConfig, recentTranscript: String): Boolean {
        if (recentTranscript.isBlank()) return false
        val judgeModel = model.copy(
            pureChatMode = true,
            toolCallMode = ToolCallMode.DISABLED,
            maxTokens = 64,
        )
        val raw = runCatching {
            caller.call(judgeModel, listOf(judgeSystem(), judgeUser(recentTranscript)))
        }.getOrNull() ?: return false
        return parseDone(raw)
    }

    internal fun parseDone(raw: String): Boolean {
        // 剥代码围栏、截首个 {...}，严格布尔解析（审阅 nit：contains("true") 会误匹配 "not true" 类文本）
        val body = raw.substringAfter("{", "").substringBeforeLast("}", "").ifBlank { return false }
        return runCatching {
            (json.parseToJsonElement("{$body}") as? JsonObject)
                ?.get("done")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        }.getOrDefault(false)
    }

    private fun judgeSystem(): ApiMessage = ApiMessage(
        role = "system",
        content = "你是上下文管家。只依据给出的近期对话判断：用户当前这轮任务是否已告一段落" +
            "（已给出最终答复/验收通过/用户已确认），还是仍在进行中。" +
            "只回一行 JSON：{\"done\": true} 或 {\"done\": false}，不要输出任何其他内容。",
    )

    private fun judgeUser(recentTranscript: String): ApiMessage = ApiMessage(
        role = "user",
        content = "近期对话（更早内容已省略）：\n$recentTranscript",
    )

    companion object {
        /** 换段的保留起点：最后一个用户消息与预算折叠线中取更深者，至少留一条。 */
        fun switchKeepFromIndex(messages: List<HarnessMessage>, computedKeepFromIndex: Int): Int {
            val lastUser = messages.indexOfLast { it is UserMessage }
            return maxOf(lastUser, computedKeepFromIndex).coerceIn(0, (messages.size - 1).coerceAtLeast(0))
        }

        /** 近期转录：单条截 1500 字符，合计约 [maxChars]，只保留模型可见的关键正文。 */
        fun recentTranscript(messages: List<HarnessMessage>, maxChars: Int = 6_000): String {
            val lines = mutableListOf<String>()
            var remaining = maxChars
            for (message in messages.asReversed()) {
                if (remaining <= 0) break
                val line = when (message) {
                    is UserMessage -> "用户: " + message.text
                    is AssistantText -> "助手: " + message.textContent.toString()
                    is ToolCall -> "工具调用: ${message.tool.name}"
                    is ToolResult -> "工具结果: " + message.output.toString().take(200) +
                        (if (message.success) "" else "（失败）")
                    else -> null
                } ?: continue
                val clipped = line.take(1_500)
                if (clipped.length > remaining) {
                    lines += clipped.take(remaining)
                    break
                }
                lines += clipped
                remaining -= clipped.length
            }
            return lines.asReversed().joinToString("\n")
        }
    }
}
