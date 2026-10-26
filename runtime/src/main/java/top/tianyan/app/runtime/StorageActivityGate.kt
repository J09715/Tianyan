package top.tianyan.app.runtime

/** Commands may run concurrently; cleanup needs an idle runtime and prevents new launches. */
internal class StorageActivityGate {
    private val monitor = Any()
    private var active = 0
    private var cleaning = false

    suspend fun <T> activity(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning) { "正在清理存储，请稍后再启动任务" }
            active++
        }
        try { return block() } finally { synchronized(monitor) { active-- } }
    }

    suspend fun <T> cleanup(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning && active == 0) { "有命令或构建正在运行，请结束后再清理" }
            cleaning = true
        }
        try { return block() } finally { synchronized(monitor) { cleaning = false } }
    }
}
