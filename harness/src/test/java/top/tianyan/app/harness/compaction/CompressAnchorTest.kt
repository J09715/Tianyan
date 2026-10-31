package top.tianyan.app.harness.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.UserMessage

/**
 * compress 工具的锚点解析测试：anchor 必须原样、唯一地摘自某条用户消息，
 * mode 决定压缩边界；校验失败返回可回写给模型的说明。
 */
class CompressAnchorTest {

    private val messages: List<HarnessMessage> = listOf(
        UserMessage("u1", 1L, "帮我分析一下项目的调用链结构，重点看 harness 模块"),
        AssistantText("a1", 2L, "好的"),
        UserMessage("u2", 3L, "帮我分析一下项目的调用链结构，重点看 runtime 模块"),
        AssistantText("a2", 4L, "完成"),
        UserMessage("u3", 5L, "继续，把结果整理成文档"),
    )

    @Test
    fun `before mode folds everything before the anchor turn`() {
        val result = CompactionManager.resolveCompressAnchor(messages, "before", "重点看 runtime 模块")
        val keepFromIndex = (result as CompressAnchorResult.Resolved).keepFromIndex
        // u2 的下标是 2：u1 及其回复被折叠，u2 及之后保留
        assertEquals(2, keepFromIndex)
    }

    @Test
    fun `after mode folds up to the active turn`() {
        val result = CompactionManager.resolveCompressAnchor(messages, "after", "重点看 harness 模块")
        val keepFromIndex = (result as CompressAnchorResult.Resolved).keepFromIndex
        // 进行中轮次 u3 的下标是 4：之前全部折叠
        assertEquals(4, keepFromIndex)
    }

    @Test
    fun `duplicate excerpt is rejected with uniqueness guidance`() {
        val result = CompactionManager.resolveCompressAnchor(messages, "before", "帮我分析一下项目的调用链结构")
        assertTrue(result is CompressAnchorResult.Invalid)
        assertTrue((result as CompressAnchorResult.Invalid).message.contains("2 条"))
    }

    @Test
    fun `missing anchor is rejected`() {
        val result = CompactionManager.resolveCompressAnchor(messages, "before", "这段话不存在于任何用户消息里")
        assertTrue(result is CompressAnchorResult.Invalid)
    }

    @Test
    fun `short or invalid mode or anchor is rejected`() {
        assertTrue(CompactionManager.resolveCompressAnchor(messages, "before", "短锚点") is CompressAnchorResult.Invalid)
        assertTrue(CompactionManager.resolveCompressAnchor(messages, "middle", "重点看 runtime 模块") is CompressAnchorResult.Invalid)
    }

    @Test
    fun `anchor on first message in before mode has nothing to fold`() {
        val result = CompactionManager.resolveCompressAnchor(messages, "before", "重点看 harness 模块")
        assertTrue(result is CompressAnchorResult.Invalid)
        assertTrue((result as CompressAnchorResult.Invalid).message.contains("没有可压缩的内容"))
    }
}
