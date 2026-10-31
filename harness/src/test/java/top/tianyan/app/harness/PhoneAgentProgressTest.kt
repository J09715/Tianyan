package top.tianyan.app.harness

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAgentProgressTest {
    @Test fun stopsRepeatedActionsOnlyOnUnchangedFrames() {
        val progress = PhoneAgentProgress()
        val tap = PhoneAgentAction.Tap(100, 200)
        assertNull(progress.beforeAction(tap, "a"))
        assertNull(progress.beforeAction(tap, "b"))
        assertNull(progress.beforeAction(tap, "b"))
        assertNotNull(progress.beforeAction(tap, "b"))
    }
    @Test fun enforcesWaitAndFailureBudgets() {
        val progress = PhoneAgentProgress()
        repeat(3) { assertNull(progress.beforeAction(PhoneAgentAction.Wait(), "same")) }
        assertNotNull(progress.beforeAction(PhoneAgentAction.Wait(), "same"))
        assertNull(progress.afterAction(false))
        assertNotNull(progress.beforeAction(PhoneAgentAction.Finish("完成"), "same"))
        assertNotNull(progress.afterAction(false))
        assertNull(progress.beforeAction(PhoneAgentAction.TakeOver("检查连接"), "same"))
        assertNull(progress.afterAction(true))
        assertNull(progress.beforeAction(PhoneAgentAction.Finish("完成"), "changed"))
    }
}
