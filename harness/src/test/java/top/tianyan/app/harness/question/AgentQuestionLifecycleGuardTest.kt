package top.tianyan.app.harness.question

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提问工具的取消与后台 Lane 守卫（源码契约断言）。
 *
 * 这两条都是**只有运行时才暴露**的悬挂/失效缺陷，单元测试难以直接触发，
 * 因此用源码断言把契约钉住：
 *
 *  1. 取消会话时必须显式唤醒挂起的提问。仅靠 job.cancel() 不够——
 *     协程进入取消态后 ask() 的 finally 要等被调度才清 pending，
 *     期间提问卡片留在界面上，用户点了也提交不进去。
 *  2. 后台 Lane（allowApprovalRequest=false，没有可作答的界面通道）
 *     必须拒绝提问，否则协程永久悬挂、子智能体永远不返回。
 *     对齐 DSH 的 DELEGATED_CALLER。
 */
class AgentQuestionLifecycleGuardTest {

    private val loopSource = File(
        "src/main/java/top/tianyan/app/harness/HarnessLoop.kt",
    )

    private val executorSource = File(
        "src/main/java/top/tianyan/app/harness/ToolExecutor.kt",
    )

    @Test
    fun harnessLoopSourcesAreReachable() {
        assertTrue("找不到 HarnessLoop.kt：${loopSource.absolutePath}", loopSource.isFile)
        assertTrue("找不到 ToolExecutor.kt：${executorSource.absolutePath}", executorSource.isFile)
    }

    @Test
    fun cancelPathWakesThePendingQuestion() {
        val text = loopSource.readText()
        assertTrue(
            "cancel() 必须调用 settlePendingQuestionForCancel 唤醒挂起提问，" +
                "否则停止生成后提问卡片不会消失、作答也无法提交",
            text.contains("settlePendingQuestionForCancel"),
        )
        // 必须真的调 broker.cancel
        assertTrue(
            "settlePendingQuestionForCancel 必须实际调用 questionBroker.cancel",
            Regex("""fun settlePendingQuestionForCancel[\s\S]{0,400}questionBroker\.cancel""").containsMatchIn(text),
        )
    }

    /**
     * withLock 的返回值是 lambda 最后一行表达式。如果把唤醒调用插在
     * sessionJobs[...] 之后，`job` 会变成 Unit，后面的 job?.cancelAndJoin()
     * 就永远不生效——整个取消静默失效。这条断言锁住调用顺序。
     */
    @Test
    fun wakeCallPrecedesJobCancelSoTheLockStillReturnsTheJob() {
        val text = loopSource.readText()
        val wakeIndex = text.indexOf("settlePendingQuestionForCancel(sessId)")
        val jobIndex = text.indexOf("sessionJobs[sessId]?.also { it.cancel() }")
        assertTrue("找不到唤醒调用", wakeIndex >= 0)
        assertTrue("找不到 sessionJobs 取消调用", jobIndex >= 0)
        assertTrue(
            "唤醒调用必须排在 sessionJobs[...].also{...} 之前：" +
                "withLock 返回最后一行表达式，插在末尾会让 job 变成 Unit，" +
                "job?.cancelAndJoin() 静默失效。",
            wakeIndex < jobIndex,
        )
    }

    @Test
    fun backgroundLanesCannotAskTheUser() {
        val text = executorSource.readText()
        assertTrue(
            "ToolExecutor 必须拒绝后台 Lane 的提问（没有可作答通道），" +
                "否则协程永久悬挂、子智能体永不返回",
            Regex("""ASK_USER\s*&&\s*!allowApprovalRequest""").containsMatchIn(text),
        )
        assertTrue(
            "拒绝理由应引导子智能体把问题写进最终结果，交由主智能体询问",
            text.contains("交由主智能体"),
        )
    }
}
