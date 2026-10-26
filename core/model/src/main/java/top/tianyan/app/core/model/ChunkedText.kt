package top.tianyan.app.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Immutable text snapshot. Appends share sealed blocks; only the small active tail is copied. */
@Serializable(with = ChunkedTextSerializer::class)
class ChunkedText private constructor(
    private val blocks: List<String>,
    private val tail: String,
    private val lineage: Any,
    override val length: Int,
    val hasText: Boolean,
    private val source: String? = null,
) : CharSequence {
    @Volatile private var flattened: String? = source
    private val contentHash by lazy {
        var hash = 0
        forEachChunk { chunk -> chunk.forEach { hash = 31 * hash + it.code } }
        hash
    }

    override fun get(index: Int): Char {
        require(index in 0 until length)
        source?.let { return it[index] }
        val block = index / BLOCK_CHARS
        return if (block < blocks.size) blocks[block][index % BLOCK_CHARS]
        else tail[index % BLOCK_CHARS]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): String {
        require(startIndex in 0..endIndex && endIndex <= length)
        return buildString(endIndex - startIndex) {
            forEachChunk(startIndex, endIndex) { append(it) }
        }
    }

    fun forEachChunk(start: Int = 0, end: Int = length, visit: (String) -> Unit) {
        require(start in 0..end && end <= length)
        source?.let {
            if (start < end) visit(if (start == 0 && end == length) it else it.substring(start, end))
            return
        }
        var offset = start
        while (offset < end) {
            val index = offset / BLOCK_CHARS
            val chunk = if (index < blocks.size) blocks[index] else tail
            val from = offset % BLOCK_CHARS
            val until = minOf(chunk.length, from + end - offset)
            visit(if (from == 0 && until == chunk.length) chunk else chunk.substring(from, until))
            offset += until - from
        }
    }

    /** Only snapshots from the same append-only builder can skip prefix verification. */
    fun isExtensionOf(previous: ChunkedText): Boolean = lineage === previous.lineage && length >= previous.length

    override fun toString(): String = flattened ?: buildString(length) {
        blocks.forEach { append(it) }
        append(tail)
    }.also { flattened = it }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkedText || length != other.length) return false
        if (lineage === other.lineage) return true
        if (source != null && other.source != null) return source == other.source
        if (source == null && other.source == null) return tail == other.tail && blocks == other.blocks
        return indices.all { this[it] == other[it] }
    }

    override fun hashCode(): Int = source?.hashCode() ?: contentHash

    class Builder {
        private var blocks: List<String> = emptyList()
        private val tail = StringBuilder()
        private var lineage = Any()
        var length: Int = 0
            private set
        private var hasText = false

        fun append(text: String) {
            var offset = 0
            hasText = hasText || text.any { !it.isWhitespace() }
            while (offset < text.length) {
                val count = minOf(BLOCK_CHARS - tail.length, text.length - offset)
                tail.append(text, offset, offset + count)
                offset += count
                length += count
                if (tail.length == BLOCK_CHARS) {
                    blocks = blocks + tail.toString()
                    tail.clear()
                }
            }
        }

        fun snapshot(): ChunkedText = ChunkedText(blocks, tail.toString(), lineage, length, hasText)

        fun clear() {
            blocks = emptyList()
            tail.clear()
            length = 0
            hasText = false
            lineage = Any()
        }
    }

    companion object {
        private const val BLOCK_CHARS = 2_048
        val EMPTY: ChunkedText = Builder().snapshot()
        fun of(text: String): ChunkedText = if (text.isEmpty()) EMPTY
            else ChunkedText(emptyList(), "", Any(), text.length, text.any { !it.isWhitespace() }, source = text)
    }
}

/** Preserve the existing JSON string field; block storage is only an in-memory detail. */
object ChunkedTextSerializer : KSerializer<ChunkedText> {
    override val descriptor = PrimitiveSerialDescriptor("ChunkedText", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ChunkedText) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): ChunkedText = ChunkedText.of(decoder.decodeString())
}
