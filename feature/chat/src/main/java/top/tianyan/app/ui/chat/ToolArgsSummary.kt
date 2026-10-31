package top.tianyan.app.ui.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

/**
 * 工具调用的**人类可读**摘要。
 *
 * 为什么不能直接 `key=value.toString()`：JsonPrimitive.toString() 会带上原始引号与转义
 * （`command="echo \"=====\""`），一行里塞满反斜杠，既难读又浪费宽度 —— 这是之前
 * 工具行看起来「乱」的主因。
 *
 * 按工具类型取最有意义的字段：
 * - base/process：命令首行（用户真正关心的就是「跑了什么」）
 * - read/write/edit：文件路径
 * - download：URL
 * - 其余：按 key=value 拼接，但值取原始文本并压平换行
 */
internal fun toolArgsSummary(call: ToolCall): String {
    fun raw(key: String): String? =
        (call.args[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // 优先取该工具最有信息量的单个字段：一行只说一件事，比堆三个 key=value 更清楚。
    val primary = when (call.tool) {
        HarnessTool.BASE, HarnessTool.PROCESS -> raw("command") ?: raw("action")
        HarnessTool.READ, HarnessTool.WRITE, HarnessTool.EDIT -> raw("path")
        HarnessTool.DOWNLOAD -> raw("url") ?: raw("path")
        HarnessTool.HOST -> raw("action")
        HarnessTool.MEMORY, HarnessTool.PLAN, HarnessTool.SCRATCHPAD -> raw("action")
        HarnessTool.LOAD_SKILL, HarnessTool.LOAD_RULE -> raw("name") ?: raw("id")
        HarnessTool.SUBAGENT -> raw("task") ?: raw("prompt")
        HarnessTool.RED_TEAM -> raw("action")
        else -> null
    }
    if (!primary.isNullOrBlank()) return primary

    return call.args.toMap().entries.take(2)
        .joinToString(" · ") { (key, value) ->
            val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: value.toString()
            "$key=${text.replace(Regex("\\s+"), " ").take(40)}"
        }
}

