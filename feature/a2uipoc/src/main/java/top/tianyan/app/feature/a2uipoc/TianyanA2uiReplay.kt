package top.tianyan.app.feature.a2uipoc

/**
 * 同一张卡片滚出再滚回时不要重复投喂；模型删掉界面后，旧卡片也不能把它建回来。
 * 新的工具调用（另一把 [replayKey]）可以再次创建。
 */
internal class TianyanA2uiReplay {
    private val seen = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_SEEN
    }
    private val suppressed = mutableSetOf<String>()

    fun shouldSkip(replayKey: String?, contentKey: String, createdSurfaceId: String?): Boolean {
        if (seen.containsKey(replayKey ?: contentKey)) return true
        return replayKey == null && createdSurfaceId != null && createdSurfaceId in suppressed
    }

    fun record(
        replayKey: String?,
        contentKey: String,
        createdSurfaceId: String?,
        deletedSurfaceIds: Set<String>,
    ) {
        if (createdSurfaceId != null) suppressed.remove(createdSurfaceId)
        seen[replayKey ?: contentKey] = createdSurfaceId.orEmpty()
        suppressed.addAll(deletedSurfaceIds)
    }

    fun release(surfaceIds: Set<String>) {
        if (surfaceIds.isEmpty()) return
        suppressed.removeAll(surfaceIds)
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value in surfaceIds) iterator.remove()
        }
    }

    private companion object {
        const val MAX_SEEN = 1024
    }
}
