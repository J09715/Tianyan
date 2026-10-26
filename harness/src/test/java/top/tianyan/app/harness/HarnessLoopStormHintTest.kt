package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * storm breaker 软收敛提示：连续失败达到阈值才触发，早于硬熔断给模型一次自纠机会。
 */
class HarnessLoopStormHintTest {

    @Test
    fun `no hint below threshold`() {
        assertNull(HarnessLoop.stormHintFor(0))
        assertNull(HarnessLoop.stormHintFor(1))
        assertNull(HarnessLoop.stormHintFor(2))
    }

    @Test
    fun `hint fires at threshold and includes round count`() {
        val hint = HarnessLoop.stormHintFor(HarnessLoop.STORM_HINT_THRESHOLD)!!
        assertTrue(hint.contains("连续 ${HarnessLoop.STORM_HINT_THRESHOLD} 轮"))
        assertTrue(hint.contains("反思"))
        // 更高失败次数同样可用（每次运行只注入一次的幂等性由调用方状态保证）
        assertEquals(
            hint.replace("${HarnessLoop.STORM_HINT_THRESHOLD} 轮", "5 轮"),
            HarnessLoop.stormHintFor(5)!!,
        )
    }
}
