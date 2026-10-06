package top.tianyan.app.runtime.browser.hook

/**
 * Network request and response body cache with a UTF-8 byte budget and LRU eviction.
 */
class NetworkBodyStore(
    private val totalBudgetBytes: Long = 6L * 1024 * 1024,
) {
    data class NetworkBody(
        val id: String,
        val tabId: String,
        val requestBody: String,
        val responseBody: String,
        val at: Long = System.currentTimeMillis(),
    ) {
        val bytes: Long
            get() = requestBody.toByteArray(Charsets.UTF_8).size.toLong() +
                responseBody.toByteArray(Charsets.UTF_8).size.toLong()
    }

    private data class Entry(val body: NetworkBody, val bytes: Long)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private var totalBytes = 0L

    init {
        require(totalBudgetBytes >= 0) { "totalBudgetBytes must not be negative" }
    }

    fun put(body: NetworkBody) {
        val bytes = body.bytes
        if (bytes > totalBudgetBytes) return
        synchronized(lock) {
            removeLocked(body.id)
            map[body.id] = Entry(body, bytes)
            totalBytes += bytes
            evictLocked()
        }
    }

    fun get(id: String): NetworkBody? = synchronized(lock) { map[id]?.body }

    fun size(): Int = synchronized(lock) { map.size }

    fun totalStoredBytes(): Long = synchronized(lock) { totalBytes }

    fun clear() {
        synchronized(lock) {
            map.clear()
            totalBytes = 0
        }
    }

    fun clearForTab(tabId: String) {
        synchronized(lock) {
            val iterator = map.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.body.tabId == tabId) {
                    totalBytes -= entry.bytes
                    iterator.remove()
                }
            }
        }
    }

    private fun removeLocked(id: String) {
        map.remove(id)?.let { removed -> totalBytes -= removed.bytes }
    }

    private fun evictLocked() {
        val iterator = map.values.iterator()
        while (totalBytes > totalBudgetBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            totalBytes -= oldest.bytes
            iterator.remove()
        }
    }
}
