package top.tianyan.app.harness.effects

/** Model-side presentation only. Call after redaction; archive the input before losing evidence. */
internal object TerminalOutputCompressor {
    private const val MAX_INLINE_LINES = 120
    private const val HEAD_LINES = 30
    private const val TAIL_LINES = 70
    private const val MAX_INLINE_CHARS = 12_000

    fun compress(redactedOutput: String): String = compressNormalized(normalize(redactedOutput))

    /** Production caller normalizes and redacts before passing text here. */
    fun compressNormalized(redactedText: String): String {
        val folded = foldOverlongLines(redactedText)
        val lines = folded.lines()
        if (lines.size <= MAX_INLINE_LINES && folded.length <= MAX_INLINE_CHARS) return folded
        val headCount = minOf(HEAD_LINES, (lines.size / 3).coerceAtLeast(1))
        val head = keepHeadWholeLines(lines.take(headCount).joinToString("\n"), MAX_INLINE_CHARS / 4)
        val tail = keepTailWholeLines(lines.drop(headCount).takeLast(TAIL_LINES).joinToString("\n"), MAX_INLINE_CHARS * 3 / 4)
        val omitted = (lines.size - head.lines().size - tail.lines().size).coerceAtLeast(0)
        return "$head\n\n[终端输出已折叠：整理后共 ${lines.size} 行，省略中间 $omitted 行；保留首尾上下文。]\n\n$tail"
    }

    /** Strip CSI/OSC controls; fold CR refreshes without changing the real PTY stream. */
    internal fun normalize(text: String): String = buildString(text.length) {
        var index = 0
        var lineStart = 0
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '\r' -> if (text.getOrNull(index) != '\n') setLength(lineStart)
                '\n' -> { append(char); lineStart = length }
                '\b' -> if (length > lineStart) setLength(length - 1)
                '\u001b', '\u009b', '\u009d' -> {
                    val kind = when (char) {
                        '\u009b' -> '['
                        '\u009d' -> ']'
                        else -> text.getOrNull(index++)
                    }
                    when (kind) {
                        '[' -> while (index < text.length) {
                            if (text[index++] in '@'..'~') break
                        }
                        ']', 'P', '^', '_' -> while (index < text.length) {
                            val control = text[index++]
                            if (control == '\u0007' || control == '\u009c') break
                            if (control == '\u001b' && text.getOrNull(index) == '\\') { index++; break }
                        }
                        else -> {
                            // Other ESC sequences may have intermediate bytes (e.g. ESC ( B).
                            if (kind != null && kind in ' '..'/') {
                                while (index < text.length && text[index] in ' '..'/') index++
                                if (index < text.length) index++
                            }
                        }
                    }
                }
                else -> if (char == '\t' || char >= ' ' && char !in '\u007f'..'\u009f') append(char)
            }
        }
    }
}
