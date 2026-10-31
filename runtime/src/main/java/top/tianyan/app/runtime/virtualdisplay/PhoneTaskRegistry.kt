package top.tianyan.app.runtime.virtualdisplay

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class PhoneTaskState { RUNNING, PAUSED, NEEDS_USER, COMPLETED, FAILED, CANCELLED }

/** 一个 session 同时只允许一个手机任务。revision 防止人工操作后执行旧截图的动作。 */
class PhoneTaskRegistry {
    private val active = ConcurrentHashMap<String, PhoneTaskControl>()
    fun start(session: String, job: Job): PhoneTaskControl? {
        val task = PhoneTaskControl(job)
        return task.takeIf { active.putIfAbsent(session, task) == null }
    }
    fun get(session: String): PhoneTaskControl? = active[session]
    fun release(session: String, task: PhoneTaskControl) { active.remove(session, task) }
    fun pause(session: String, manual: Boolean = false) { active[session]?.pause(manual) }
    fun cancel(session: String) { active[session]?.cancel() }
}

class PhoneTaskControl(private val job: Job) {
    private val mutableState = MutableStateFlow(PhoneTaskState.RUNNING)
    val state: StateFlow<PhoneTaskState> = mutableState.asStateFlow()
    private var revision = 0L
    private var touches = 0L
    @Synchronized fun manualRevision(): Long = touches

    @Synchronized fun pause(manual: Boolean = false) {
        if (mutableState.value == PhoneTaskState.RUNNING || mutableState.value == PhoneTaskState.PAUSED) {
            if (manual) touches++
            revision++
            mutableState.value = PhoneTaskState.PAUSED
        }
    }
    @Synchronized fun resume() {
        if (mutableState.value == PhoneTaskState.PAUSED) {
            revision++
            mutableState.value = PhoneTaskState.RUNNING
        }
    }
    @Synchronized fun cancel() {
        finish(PhoneTaskState.CANCELLED)
        job.cancel(CancellationException("用户停止了手机操作任务"))
    }
    @Synchronized fun finish(state: PhoneTaskState) {
        revision++
        mutableState.value = state
    }
    @Synchronized fun isCurrent(version: Long): Boolean =
        version == revision && mutableState.value == PhoneTaskState.RUNNING

    suspend fun awaitReady(): Long {
        while (true) {
            currentCoroutineContext().ensureActive()
            state.first { it != PhoneTaskState.PAUSED }
            synchronized(this) {
                if (mutableState.value == PhoneTaskState.RUNNING) return revision
                if (mutableState.value != PhoneTaskState.PAUSED) throw CancellationException("手机操作任务已结束")
            }
        }
    }
}
