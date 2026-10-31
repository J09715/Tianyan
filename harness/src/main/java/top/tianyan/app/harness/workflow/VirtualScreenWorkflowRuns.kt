package top.tianyan.app.harness.workflow

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskControl
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskState
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskRegistry

/** Retain screen ownership and HUD pause/stop across gaps between DAG nodes. */
class VirtualScreenWorkflowRuns(private val registry: PhoneTaskRegistry) {
    private class Run(val job: Job) {
        val screens = ConcurrentHashMap<String, Screen>()
    }
    internal class Screen(val control: PhoneTaskControl) {
        val lock = Mutex()
        var revision: Long? = null
    }
    private val runs = ConcurrentHashMap<String, Run>()

    fun begin(executionId: String, job: Job) { check(runs.putIfAbsent(executionId, Run(job)) == null) }

    internal fun screen(executionId: String, session: String): Screen {
        val run = runs[executionId] ?: error("虚拟屏工作流尚未开始")
        return run.screens.computeIfAbsent(session) {
            Screen(registry.start(session, run.job)
                ?: error("虚拟屏会话 $session 正在执行其他手机任务"))
        }
    }

    internal fun beforeClose(executionId: String, session: String) {
        runs[executionId]?.screens?.remove(session)?.let {
            registry.release(session, it.control)
            it.control.finish(PhoneTaskState.COMPLETED)
        }
    }

    fun end(executionId: String, state: PhoneTaskState) {
        runs.remove(executionId)?.screens?.forEach { (session, screen) ->
            screen.control.finish(state)
            registry.release(session, screen.control)
        }
    }
}
