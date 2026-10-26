package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns both running and queued replies. A stop fences admission until cancellation settles. */
internal class GroupReplyQueue(private val scope: CoroutineScope) {
    private val guard = Any()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    private val locks = mutableMapOf<String, Mutex>()
    private val stopping = mutableSetOf<String>()

    fun launch(groupId: String, key: String, block: suspend () -> Unit): Job? = synchronized(guard) {
        if (groupId in stopping) return@synchronized null
        val lock = locks.getOrPut(key) { Mutex() }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                block()
            }
        }
        jobs.getOrPut(groupId) { mutableSetOf() }.add(job)
        job.invokeOnCompletion {
            synchronized(guard) {
                jobs[groupId]?.let { groupJobs ->
                    groupJobs.remove(job)
                    if (groupJobs.isEmpty()) jobs.remove(groupId)
                }
            }
        }
        job.start()
        job
    }

    /** Round turns use the same per-(bot, group) lock as pump deliveries, so they never overlap. */
    suspend fun <T> withLock(groupId: String, key: String, block: suspend () -> T): T {
        val lock = synchronized(guard) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    suspend fun cancel(groupId: String) {
        val pending = synchronized(guard) {
            stopping.add(groupId)
            jobs[groupId].orEmpty().toList().also { list -> list.forEach { it.cancel() } }
        }
        try {
            pending.forEach { it.cancelAndJoin() }
        } finally {
            synchronized(guard) { stopping.remove(groupId) }
        }
    }
}
