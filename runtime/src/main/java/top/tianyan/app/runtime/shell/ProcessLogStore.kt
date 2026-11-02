package top.tianyan.app.runtime.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Bounded process logs. All read-modify-write operations share one lock.
 *
 * 追加路径不再整表复制：每份日志用 [ArrayDeque] 顺序入队、字符数增量维护，
 * `append` 是 O(1) 摊销（只在越过容量/字符预算时从队头淘汰），
 * 且**无人订阅时不生成列表快照** —— 服务进程 200 行/s 的输出过去会做
 * 2 次整表拷贝 + 3 次全量长度求和 + 每次向 StateFlow 发一份新 List，
 * 现在降到 0 分配（有订阅者时每行仍只复制一份 ≤500 行的引用列表）。
 * `prune`（全表扫描）只在条目数越界或 60s 定时器/释放时执行，不再逐行调用。
 */
internal class ProcessLogStore(
    private val maxIdleEntries: Int = 64,
    private val maxTotalChars: Int = 4 * 1024 * 1024,
    private val maxEntryChars: Int = 256 * 1024,
    private val maxLineChars: Int = 16 * 1024,
    private val retentionMillis: Long = 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 日志正文与字符计数放在一起：字符数增量维护，读写都不必再遍历整表求和。 */
    private class Entry(val flow: MutableStateFlow<List<String>>, var lastUsed: Long, var users: Int = 0) {
        val lines = ArrayDeque<String>(MAX_LINES)
        var chars = 0
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** 别名（工具 id → 进程 id）：同一次运行只保存/追写一份日志，避免双 key 重复 ×2 开销。 */
    private val aliases = HashMap<String, String>()
    private var totalChars = 0

    /** 登记别名：`observeLogs/getLogs/clearLogs` 传工具 id 时解析到真实进程 id。 */
    @Synchronized fun alias(from: String, to: String) {
        if (from.isNotBlank() && from != to) aliases[from] = to
    }

    @Synchronized fun retain(key: String) { entry(key).users++ }

    @Synchronized fun release(key: String) {
        entries[resolve(key)]?.let { it.users = (it.users - 1).coerceAtLeast(0); it.lastUsed = clock() }
        prune()
    }

    @Synchronized fun append(key: String, line: String) {
        val entry = entry(key)
        val bounded = if (line.length > maxLineChars) line.takeLast(maxLineChars) else line
        entry.lines.addLast(bounded)
        entry.chars += bounded.length
        totalChars += bounded.length
        while (entry.lines.size > MAX_LINES || entry.chars > maxEntryChars) {
            val dropped = entry.lines.removeFirst()
            entry.chars -= dropped.length
            totalChars -= dropped.length
        }
        publish(entry)
        // 全局预算只在真正越界时处理；条目数越界才做全表 prune（原来每行都跑一次）。
        if (totalChars > maxTotalChars) trimOtherEntries(entry)
        if (entries.size > maxIdleEntries) prune()
    }

    fun observe(key: String): Flow<List<String>> = flow {
        // Resolve at collection time and pin through cancellation. A previously obtained
        // Flow must still work if its old idle cache entry was evicted before subscription.
        val observed = synchronized(this@ProcessLogStore) {
            entry(key).also {
                it.users++
                // 订阅瞬间补一份快照：无人观察期间的追加只进了队列，没有发射过。
                it.flow.value = it.lines.toList()
            }.flow
        }
        try { emitAll(observed) } finally { release(key) }
    }

    @Synchronized fun get(key: String): List<String> = entries[resolve(key)]?.lines?.toList().orEmpty()

    @Synchronized fun clear(key: String) {
        entries[resolve(key)]?.let { entry ->
            totalChars -= entry.chars
            entry.lines.clear()
            entry.chars = 0
            entry.flow.value = emptyList()
        }
    }

    @Synchronized fun prune() {
        val now = clock()
        val idle = entries.entries.filter { it.value.users == 0 && it.value.flow.subscriptionCount.value == 0 }
        var excess = (idle.size - maxIdleEntries).coerceAtLeast(0)
        idle.forEach { (key, entry) ->
            if (excess > 0 || now - entry.lastUsed >= retentionMillis) {
                evict(key, entry)
                if (excess > 0) excess--
            }
        }
        if (totalChars > maxTotalChars) trimOtherEntries(null)
    }

    /** 释放超出预算的字符：按插入顺序从队头淘汰，字符计数与总量同步递减。 */
    private fun trimOtherEntries(spare: Entry?) {
        for (entry in entries.values) {
            if (totalChars <= maxTotalChars) return
            if (entry === spare) continue
            while (totalChars > maxTotalChars && entry.lines.isNotEmpty()) {
                val dropped = entry.lines.removeFirst()
                entry.chars -= dropped.length
                totalChars -= dropped.length
            }
            publish(entry)
        }
    }

    /** 只有存在订阅者才生成快照，否则只留队列内容，避免无人观察时的逐行列表拷贝。 */
    private fun publish(entry: Entry) {
        if (entry.flow.subscriptionCount.value > 0) entry.flow.value = entry.lines.toList()
    }

    private fun evict(key: String, entry: Entry) {
        totalChars -= entry.chars
        entry.lines.clear()
        entry.chars = 0
        entry.flow.value = emptyList()
        entries.remove(key)
        aliases.entries.removeAll { it.value == key }
    }

    private fun resolve(key: String): String = aliases[key] ?: key

    private fun entry(key: String): Entry =
        entries.getOrPut(resolve(key)) { Entry(MutableStateFlow(emptyList()), clock()) }
            .also { it.lastUsed = clock() }

    private companion object {
        /** 与历史行为一致：最多保留 500 行（takeLast(499) + 新行）。 */
        const val MAX_LINES = 500
    }
}
