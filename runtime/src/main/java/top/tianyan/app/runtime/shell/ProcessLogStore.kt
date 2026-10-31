package top.tianyan.app.runtime.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Bounded process logs. All read-modify-write operations share one lock. */
internal class ProcessLogStore(
    private val maxIdleEntries: Int = 64,
    private val maxTotalChars: Int = 4 * 1024 * 1024,
    private val maxEntryChars: Int = 256 * 1024,
    private val maxLineChars: Int = 16 * 1024,
    private val retentionMillis: Long = 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val flow: MutableStateFlow<List<String>>, var lastUsed: Long, var users: Int = 0)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalChars = 0

    @Synchronized fun retain(key: String) { entry(key).users++ }

    @Synchronized fun release(key: String) {
        entries[key]?.let { it.users = (it.users - 1).coerceAtLeast(0); it.lastUsed = clock() }
        prune()
    }

    @Synchronized fun append(key: String, line: String) {
        val entry = entry(key)
        val lines = (entry.flow.value.takeLast(499) + line.takeLast(maxLineChars)).toMutableList()
        var chars = lines.sumOf(String::length)
        while (chars > maxEntryChars && lines.isNotEmpty()) chars -= lines.removeAt(0).length
        replace(entry, lines)
        prune()
    }

    fun observe(key: String): Flow<List<String>> = flow {
        // Resolve at collection time and pin through cancellation. A previously obtained
        // Flow must still work if its old idle cache entry was evicted before subscription.
        val observed = synchronized(this@ProcessLogStore) {
            entry(key).also { it.users++ }.flow
        }
        try { emitAll(observed) } finally { release(key) }
    }
    @Synchronized fun get(key: String): List<String> = entries[key]?.flow?.value.orEmpty()
    @Synchronized fun clear(key: String) { entries[key]?.let { replace(it, emptyList()) } }

    @Synchronized fun prune() {
        val now = clock()
        val idle = entries.entries.filter { it.value.users == 0 && it.value.flow.subscriptionCount.value == 0 }
        var excess = (idle.size - maxIdleEntries).coerceAtLeast(0)
        idle.forEach { (key, entry) ->
            if (excess > 0 || now - entry.lastUsed >= retentionMillis) {
                replace(entry, emptyList())
                entries.remove(key)
                if (excess > 0) excess--
            }
        }
        // Preserve active/subscribed flow identities while trimming payloads to the global budget.
        entries.values.forEach { entry ->
            if (totalChars > maxTotalChars) {
                val lines = entry.flow.value.toMutableList()
                var removed = 0
                while (totalChars - removed > maxTotalChars && lines.isNotEmpty()) removed += lines.removeAt(0).length
                replace(entry, lines)
            }
        }
    }

    private fun entry(key: String): Entry = entries.getOrPut(key) { Entry(MutableStateFlow(emptyList()), clock()) }
        .also { it.lastUsed = clock() }

    private fun replace(entry: Entry, lines: List<String>) {
        totalChars += lines.sumOf(String::length) - entry.flow.value.sumOf(String::length)
        entry.flow.value = lines
    }
}
