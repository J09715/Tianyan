package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下文用量构成条的回归保护：三段之和必须恒等于已用量。
 *
 * 之前的实现直接拿原始 systemTokens 当分母，一旦系统提示估算跑飞
 * （技能正文被误算成 2.7M），分段条就整条变蓝、对话历史被压成 0。
 */
class ContextUsageSegmentsTest {

    @Test
    fun `segments always sum to used tokens`() {
        val segments = composeContextUsageSegments(
            usedTokens = 128_000,
            systemTokens = 4_200,
            toolTokens = 30_000,
        )

        assertEquals(128_000, segments.total)
        assertEquals(4_200, segments.system)
        assertEquals(30_000, segments.tool)
        assertEquals(93_800, segments.conversation)
    }

    @Test
    fun `oversized system estimate cannot exceed used tokens`() {
        val segments = composeContextUsageSegments(
            usedTokens = 128_000,
            systemTokens = 2_700_000,
            toolTokens = 6_000,
        )

        assertEquals(128_000, segments.total)
        assertEquals(128_000, segments.system)
        assertEquals(0, segments.tool)
        assertEquals(0, segments.conversation)
    }

    @Test
    fun `fresh session spends only the system prompt`() {
        val segments = composeContextUsageSegments(
            usedTokens = 4_200,
            systemTokens = 4_200,
            toolTokens = 0,
        )

        assertEquals(4_200, segments.total)
        assertEquals(0, segments.conversation)
    }

    @Test
    fun `negative inputs are clamped instead of producing negative segments`() {
        val segments = composeContextUsageSegments(
            usedTokens = -1,
            systemTokens = -5,
            toolTokens = -7,
        )

        assertEquals(0, segments.total)
        assertEquals(0, segments.system)
        assertEquals(0, segments.tool)
        assertEquals(0, segments.conversation)
    }
}
