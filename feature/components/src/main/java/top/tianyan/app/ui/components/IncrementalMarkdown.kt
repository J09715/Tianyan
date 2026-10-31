package top.tianyan.app.ui.components

import top.tianyan.app.core.model.ChunkedText

/** Commit only blank-line boundaries outside fences, matching the block parser's grammar. */
internal class IncrementalMarkdown<B>(private val parse: (String) -> List<B>) {
    private var previous: ChunkedText? = null
    private var committed: List<B> = emptyList()
    private val tail = StringBuilder()
    private val line = StringBuilder()
    private var fenced = false
    private var afterCr = false
    var scannedChars: Long = 0
        private set

    fun update(text: ChunkedText): List<B> {
        val old = previous
        if (text === old) return blocks
        if (old == null || !text.isExtensionOf(old)) reset()
        val start = previous?.length ?: 0
        text.forEachChunk(start) { chunk -> chunk.forEach(::append) }
        previous = text
        // Only the unfinished region is reparsed; committed objects retain their identity.
        blocks = committed + parse(tail.toString())
        return blocks
    }

    private var blocks: List<B> = emptyList()

    private fun append(char: Char) {
        scannedChars++
        if (char == '\n' && afterCr) { afterCr = false; return }
        afterCr = char == '\r'
        if (char != '\n' && char != '\r') {
            tail.append(char)
            line.append(char)
            return
        }
        tail.append('\n')
        val completeLine = line.toString()
        line.clear()
        if (completeLine.trimStart().startsWith("```")) fenced = !fenced
        if (completeLine.isBlank() && !fenced) {
            committed = committed + parse(tail.toString())
            tail.clear()
        }
    }

    private fun reset() {
        previous = null
        committed = emptyList()
        blocks = emptyList()
        tail.clear()
        line.clear()
        fenced = false
        afterCr = false
    }
}
