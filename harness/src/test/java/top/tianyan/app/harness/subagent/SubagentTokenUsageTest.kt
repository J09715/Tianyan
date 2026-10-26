package top.tianyan.app.harness.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.SubagentTokenUsage
import top.tianyan.app.harness.UserMessage

/**
 * 委托经济学：lane transcript 的 token 用量聚合（含缓存命中）。
 * 父汇总凭它判断"委派是否划算"，而不是只看轮数。
 */
class SubagentTokenUsageTest {

    @Test
    fun `aggregates usage across assistant rounds`() {
        val transcript = listOf(
            UserMessage("u1", 1L, "任务"),
            AssistantText("a1", 2L, "第一轮", promptTokens = 10_000, completionTokens = 500, cachedTokens = 8_000),
            AssistantText("a2", 3L, "第二轮", promptTokens = 20_000, completionTokens = 1_500, cachedTokens = 12_000),
            AssistantText("a3", 4L, "无用量轮"),
        )
        val usage = SubagentTokenUsage.extractFrom(transcript)!!
        assertEquals(30_000L, usage.promptTokens)
        assertEquals(20_000L, usage.cachedTokens)
        assertEquals(2_000L, usage.completionTokens)
        assertEquals(66, usage.cacheHitPercent)
    }

    @Test
    fun `transcript without usage data yields null`() {
        assertNull(SubagentTokenUsage.extractFrom(emptyList()))
        assertNull(SubagentTokenUsage.extractFrom(listOf(UserMessage("u1", 1L, "任务"))))
        assertNull(
            SubagentTokenUsage.extractFrom(
                listOf(AssistantText("a1", 2L, "旧数据无用量字段")),
            ),
        )
    }
}
