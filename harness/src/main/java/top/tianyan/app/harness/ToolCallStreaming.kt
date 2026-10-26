package top.tianyan.app.harness

/** 分片累积一次工具调用的 id/name/arguments（OpenAI 与 Anthropic 流式均复用）。 */
internal data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
) {
    private var lastProgressAtNanos: Long = 0L
    private var lastProgress: Triple<Int, Int, Int>? = null

    /**
     * 工具参数本身也是 SSE 分片。WRITE/EDIT 在完整 JSON 到达前不能执行，但可以从
     * 已收到的 JSON 字符串片段估算增删行数，避免大文件生成期间 UI 长时间停在“思考中”。
     */
    fun publishProgress(onProgress: (ToolCallStreamProgress) -> Unit, force: Boolean = false) {
        val normalizedName = name.trim().lowercase()
        if (normalizedName.isBlank() || arguments.isEmpty()) return
        val now = System.nanoTime()
        if (!force && lastProgressAtNanos != 0L && now - lastProgressAtNanos < TOOL_PROGRESS_INTERVAL_NANOS) return

        val progress = when (normalizedName) {
            "write" -> ToolCallStreamProgress(
                name = normalizedName,
                addedLines = countPartialJsonStringLines(arguments, "content") ?: 0,
                deletedLines = 0,
            )
            "edit" -> ToolCallStreamProgress(
                name = normalizedName,
                addedLines = countPartialJsonStringLines(arguments, "newText") ?: 0,
                deletedLines = countPartialJsonStringLines(arguments, "oldText") ?: 0,
            )
            else -> ToolCallStreamProgress(normalizedName, 0, 0)
        }.copy(argumentChars = arguments.length)
        val counts = Triple(progress.addedLines, progress.deletedLines, progress.argumentChars)
        if (force || counts != lastProgress) {
            lastProgress = counts
            lastProgressAtNanos = now
            onProgress(progress)
        }
    }

    private companion object {
        const val TOOL_PROGRESS_INTERVAL_NANOS = 200_000_000L
    }
}

/** 工具参数流的轻量进度；不参与最终执行。WRITE/EDIT 另提供行数预览。 */
data class ToolCallStreamProgress(
    val name: String,
    val addedLines: Int,
    val deletedLines: Int,
    val argumentChars: Int = 0,
)

internal fun ToolCallStreamProgress.statusText(): String = when (name) {
    "write" -> "正在生成 write · +$addedLines"
    "edit" -> "正在生成 edit · +$addedLines -$deletedLines"
    else -> "正在生成工具调用 ${name.take(80)} · $argumentChars 字符"
}

/**
 * 从尚未闭合的 JSON 参数中读取指定字符串字段的当前行数。
 * JSON 中换行通常是 `\n`；转义反斜杠 `\\n` 不应误判为新行。
 */
internal fun countPartialJsonStringLines(arguments: CharSequence, fieldName: String): Int? {
    val key = "\"$fieldName\""
    var searchFrom = 0
    while (searchFrom < arguments.length) {
        val keyStart = arguments.indexOf(key, searchFrom)
        if (keyStart < 0) return null
        var cursor = keyStart + key.length
        while (cursor < arguments.length && arguments[cursor].isWhitespace()) cursor++
        if (cursor >= arguments.length) return null
        if (arguments[cursor] != ':') {
            searchFrom = keyStart + key.length
            continue
        }
        cursor++
        while (cursor < arguments.length && arguments[cursor].isWhitespace()) cursor++
        if (cursor >= arguments.length) return null
        if (arguments[cursor] != '"') {
            searchFrom = keyStart + key.length
            continue
        }
        cursor++
        var lines = 1
        var escaped = false
        while (cursor < arguments.length) {
            val char = arguments[cursor++]
            if (escaped) {
                if (char == 'n') lines++
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '\n' -> lines++
                    '"' -> return lines
                }
            }
        }
        return lines
    }
    return null
}
