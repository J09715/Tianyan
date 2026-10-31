package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 覆盖获取连接到调用 finally 的完整期间，包含握手和等待写锁的窗口。 */
internal class McpConnectionUses {
    private var users = 0
    private var idle = CompletableDeferred<Unit>().apply { complete(Unit) }
    @Volatile var retiring = false
        private set
    @Synchronized fun acquire(): Boolean {
        if (retiring) return false
        if (users++ == 0) idle = CompletableDeferred()
        return true
    }
    @Synchronized fun release() {
        check(users > 0)
        if (--users == 0) idle.complete(Unit)
    }
    @Synchronized fun retire(): Boolean { if (retiring) return false; retiring = true; return true }
    fun retire(scope: CoroutineScope, close: suspend () -> Unit) {
        if (!retire()) return
        scope.launch {
            try { awaitIdle(); close() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cleanup remains registered for retry by its owner. */ }
        }
    }
    @Synchronized fun busy(): Boolean = users > 0
    suspend fun awaitIdle() { val signal = synchronized(this) { idle }; signal.await() }
}
