package top.tianyan.app.harness.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

/**
 * 子智能体内 base 命令的默认超时上限（秒）。
 *
 * 主会话的 base 默认超时是 10 分钟（`DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS = 600`），
 * 而单个子智能体的总预算只有 15 分钟（`SubagentOrchestrator.SUBAGENT_TIMEOUT_MS`）。
 * 两者叠加的后果是：子任务里只要有一条命令挂住（全盘 `find`、卡住的网络命令、
 * 等待交互的进程），默认就能静默吃掉 2/3 预算——父会话看到的是"子智能体完全不动"，
 * 一直等到 15 分钟兜底才收到超时结论，期间没有任何可用的中间结果。
 *
 * 子任务是定向小活，默认节奏应比主会话激进：3 分钟没跑完的命令大概率不是在正常干活。
 * 需要合法长跑（gradle/构建/大批量拷贝）的子任务可以显式传 `timeout_seconds`，
 * 本函数只补默认值，绝不覆盖模型已写明的意图。
 */
internal const val SUBAGENT_COMMAND_TIMEOUT_CAP_SECONDS = 180L

/**
 * 给子智能体内的 base 调用补一个"够用就好"的默认超时。
 *
 * 只处理两件事：不是 base 的原样返回；已显式声明 `timeout_seconds` 的原样返回
 * （尊重模型/调用方意图，也让长任务有出口）。其余情况补 [capSeconds]。
 */
internal fun ToolCall.withSubagentCommandTimeoutCap(
    capSeconds: Long = SUBAGENT_COMMAND_TIMEOUT_CAP_SECONDS,
): ToolCall {
    if (tool != HarnessTool.BASE) return this
    val explicit = args["timeout_seconds"]?.let { element ->
        runCatching { element.jsonPrimitiveText() }.getOrNull()
    }
    if (explicit != null) return this
    val patched = args.toMutableMap().apply {
        put("timeout_seconds", JsonPrimitive(capSeconds))
    }
    return copy(args = JsonObject(patched))
}

/** 宽容读取一个可能被模型写成字符串或数字的 JSON 原始值。 */
private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveText(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }?.content?.takeIf { it.isNotBlank() }
