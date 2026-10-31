package top.tianyan.app.harness

/**
 * AutoGLM 一类手机操作模型的动作。坐标是 0..1000 的相对值，左上角为原点。
 */
internal sealed class PhoneAgentAction {
    data class Tap(val x: Int, val y: Int) : PhoneAgentAction()
    data class DoubleTap(val x: Int, val y: Int) : PhoneAgentAction()
    data class LongPress(val x: Int, val y: Int) : PhoneAgentAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : PhoneAgentAction()
    data class Type(val text: String) : PhoneAgentAction()
    data class Launch(val app: String) : PhoneAgentAction()
    data class Finish(val message: String) : PhoneAgentAction()
    data class TakeOver(val message: String) : PhoneAgentAction()
    data class Note(val message: String) : PhoneAgentAction()
    data object Back : PhoneAgentAction()
    data object Home : PhoneAgentAction()
    data class Wait(val durationMs: Long = 1000L) : PhoneAgentAction()
}

/** 只解析正式回答，不执行 think 或字符串正文里的指令，多条动作一律拒绝。 */
internal fun parsePhoneAgentAction(raw: String): PhoneAgentAction? {
    if (raw.length > 32_768) return null
    val answers = Regex("<answer>([\\s\\S]*?)</answer>").findAll(raw).toList()
    if (answers.size > 1) return null
    if (answers.isEmpty() && (raw.contains("<answer>") || raw.contains("</answer>"))) return null
    val source = answers.singleOrNull()?.groupValues?.get(1)
        ?: raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
    if (answers.isEmpty() && source.contains("<think>")) return null
    val start = Regex("(?m)^\\s*(do|finish)\\s*\\(").find(source) ?: return null
    val fields = PhoneActionArguments(source.substring(start.range.last + 1)).read() ?: return null
    if (start.groupValues[1] == "finish") {
        if (fields.keys.any { it != "message" }) return null
        return fields["message"]?.string()?.let { PhoneAgentAction.Finish(it.ifBlank { "已完成" }) }
    }
    val action = fields["action"]?.string()?.trim()?.lowercase() ?: return null
    fun point(key: String) = fields[key]?.point()
    fun text(key: String) = fields[key]?.string()
    val allowed = when (action) {
        "tap", "double tap", "double_tap", "long press", "long_press" -> setOf("action", "element", "message")
        "swipe" -> setOf("action", "start", "end")
        "type", "type_name" -> setOf("action", "text")
        "launch" -> setOf("action", "app")
        "wait" -> setOf("action", "duration")
        "back", "home" -> setOf("action")
        "take_over", "take over", "interact", "note" -> setOf("action", "message")
        else -> return null
    }
    if (fields.keys.any { it !in allowed }) return null
    if (fields["message"] != null && text("message") == null) return null
    return when (action) {
        "tap", "double tap", "double_tap", "long press", "long_press" -> point("element")?.let { (x, y) ->
            text("message")?.takeIf { it.isNotBlank() }?.let { return PhoneAgentAction.TakeOver(it) }
            when (action) {
                "tap" -> PhoneAgentAction.Tap(x, y)
                "double tap", "double_tap" -> PhoneAgentAction.DoubleTap(x, y)
                else -> PhoneAgentAction.LongPress(x, y)
            }
        }
        "swipe" -> {
            val (x1, y1) = point("start") ?: return null
            val (x2, y2) = point("end") ?: return null
            PhoneAgentAction.Swipe(x1, y1, x2, y2)
        }
        "type", "type_name" -> text("text")?.let { PhoneAgentAction.Type(it) }
        "launch" -> text("app")?.takeIf { it.isNotBlank() }?.let { PhoneAgentAction.Launch(it) }
        "back" -> PhoneAgentAction.Back
        "home" -> PhoneAgentAction.Home
        "wait" -> {
            val duration = text("duration")
            if (fields.containsKey("duration") && duration == null) return null
            val seconds = duration?.let {
                Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(?:seconds?|s)?").matchEntire(it.trim())
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            } ?: 1.0
            if (!seconds.isFinite() || seconds !in 0.1..10.0) return null
            PhoneAgentAction.Wait((seconds * 1000).toLong())
        }
        "take_over", "take over", "interact" -> PhoneAgentAction.TakeOver(text("message").orEmpty().ifBlank { "需要你选择或确认" })
        "note" -> text("message")?.let { PhoneAgentAction.Note(it) }
        else -> null
    }
}

private data class PhoneArgument(val value: String, val quoted: Boolean) {
    fun string(): String? = value.takeIf { quoted }
    fun point(): Pair<Int, Int>? {
        if (quoted) return null
        val match = Regex("\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").matchEntire(value) ?: return null
        val x = match.groupValues[1].toIntOrNull() ?: return null
        val y = match.groupValues[2].toIntOrNull() ?: return null
        return (x to y).takeIf { x in 0..1000 && y in 0..1000 }
    }
}

/** 参数扫描器支持单双引号、转义、逗号以及坐标数组，不使用 eval。 */
private class PhoneActionArguments(private val source: String) {
    private var offset = 0
    private fun spaces() { while (source.getOrNull(offset)?.isWhitespace() == true) offset++ }

    fun read(): Map<String, PhoneArgument>? {
        val fields = linkedMapOf<String, PhoneArgument>()
        while (offset < source.length) {
            spaces()
            if (source.getOrNull(offset) == ')') {
                offset++
                val tail = source.substring(offset).trim()
                return fields.takeIf { tail.isEmpty() || tail == "```" }
            }
            val start = offset
            while (source.getOrNull(offset)?.let { it.isLetterOrDigit() || it == '_' } == true) offset++
            if (offset == start) return null
            val key = source.substring(start, offset)
            spaces()
            if (source.getOrNull(offset++) != '=') return null
            spaces()
            val argument = argument() ?: return null
            if (fields.put(key, argument) != null) return null
            spaces()
            when (source.getOrNull(offset)) {
                ',' -> { offset++; spaces(); if (source.getOrNull(offset) == ')') return null }
                ')' -> Unit
                else -> return null
            }
        }
        return null
    }

    private fun argument(): PhoneArgument? {
        val quote = source.getOrNull(offset) ?: return null
        if (quote == '"' || quote == '\'') {
            offset++
            val text = StringBuilder()
            while (offset < source.length) {
                val char = source[offset++]
                if (char == quote) return PhoneArgument(text.toString(), true)
                if (char != '\\') { text.append(char); continue }
                val escaped = source.getOrNull(offset++) ?: return null
                text.append(when (escaped) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    '\\', '"', '\'' -> escaped
                    else -> return null
                })
            }
            return null
        }
        if (quote != '[') return null
        val end = source.indexOf(']', offset)
        if (end < 0) return null
        return PhoneArgument(source.substring(offset, end + 1), false).also { offset = end + 1 }
    }
}

internal fun phoneAgentPoint(value: Int, size: Int): Int {
    if (size <= 1) return 0
    return (value.coerceIn(0, 1000).toLong() * (size - 1) / 1000).toInt()
}
