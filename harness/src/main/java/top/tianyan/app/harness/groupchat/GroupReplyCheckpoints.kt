package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serial, bounded crash-recovery writes, independent of live reply publication. */
internal suspend fun <T> withGroupReplyCheckpoints(
    persist: suspend (GroupReplyProgress) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    observe: suspend (checkpoint: (GroupReplyProgress) -> Unit) -> T,
): T = coroutineScope {
    val pending = Channel<GroupReplyProgress>(Channel.CONFLATED)
    var lastCheckpoint = clock()
    val writer = launch(dispatcher) {
        for (progress in pending) {
            val now = clock()
            if (now - lastCheckpoint >= 2_000) {
                persist(progress)
                lastCheckpoint = now
            }
        }
    }
    try {
        val result = observe { pending.trySend(it) }
        pending.close()
        writer.join()
        result
    } finally {
        pending.cancel()
        // A draft write must finish/cancel before a final row or a new watcher takes over.
        withContext(NonCancellable) { writer.cancelAndJoin() }
    }
}
