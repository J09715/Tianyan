package top.tianyan.app.plugin.ui.web

/**
 * Bounded incremental push for one page-owned live-service stream.
 *
 * The WebView never connects to loopback: a stream is a normal capability call whose executor
 * pushes chunks back through the documented message channel under the same request id, and the
 * final `{id, success, text}` envelope still comes from the ordinary single-shot reply path, so the
 * page tells chunks apart by their `more` flag. One stream runs per document (the session already
 * allows one in-flight call) and every chunk and the running total are bounded, so a service can
 * never flood a page. A revoked or closed document detaches the sender and stops the producer.
 */
internal class WebPluginStreamBridge(
    private val maxChunkChars: Int = MAX_CHUNK_CHARS,
    private val maxTotalChars: Int = MAX_TOTAL_CHARS,
) {
    private val monitor = Any()
    private var active: String? = null
    private var sender: ((String) -> Boolean)? = null
    private var total = 0
    private var closed = false

    init {
        require(maxChunkChars in 64..MAX_CHUNK_CHARS && maxTotalChars in maxChunkChars..MAX_TOTAL_CHARS)
    }

    /** Binds the reply channel of one call; false when another stream is already attached. */
    fun attach(id: String, send: (String) -> Boolean): Boolean = synchronized(monitor) {
        if (closed || active != null) false else {
            active = id
            sender = send
            total = 0
            true
        }
    }

    /** Bounded chunk push; false stops the producer once a budget or the document is spent. */
    fun push(chunk: String): Boolean = synchronized(monitor) {
        if (closed || chunk.isEmpty() || chunk.length > maxChunkChars || total + chunk.length > maxTotalChars) return false
        val id = active ?: return false
        val send = sender ?: return false
        total += chunk.length
        send(WebPluginMessages.encodeChunk(id, chunk))
    }

    /** Releases the stream slot after the call settles (result or cancellation). */
    fun detach() = synchronized(monitor) {
        active = null
        sender = null
        total = 0
    }

    fun close() = synchronized(monitor) {
        closed = true
        active = null
        sender = null
        total = 0
    }

    private companion object {
        const val MAX_CHUNK_CHARS = 4_096
        const val MAX_TOTAL_CHARS = 65_536
    }
}
