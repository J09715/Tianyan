package top.tianyan.app.harness

import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.SubagentTaskSpec
import top.tianyan.app.harness.subagent.SubagentTermination

class SubagentBackgroundTextTest {
    @Test
    fun `background start text tells model not to poll`() {
        val text = backgroundStartedText(
            listOf(
                SubagentTaskSpec(taskName = "审查", role = "reviewer", prompt = "检查"),
                SubagentTaskSpec(taskName = "测试", role = "tester", prompt = "运行测试"),
            ),
        )

        assertTrue(text.contains("审查"))
        assertTrue(text.contains("测试"))
        assertTrue(text.contains("后台"))
        assertTrue(text.contains("不要等待、轮询进度或重复派发"))
    }

    @Test
    fun `background completion text includes resume guidance only for partial batch`() {
        val success = backgroundCompletionText(true, "### 全部成功")
        val partial = backgroundCompletionText(false, "### 部分成功")

        assertTrue(success.contains("后台子任务已完成"))
        assertTrue(success.contains("### 全部成功"))
        assertTrue(!success.contains("task_id 续跑"))
        assertTrue(partial.contains("task_id 续跑"))
    }

    @Test
    fun `summary exposes task id for resumption`() {
        val outcome = SubagentOrchestrator.SubagentExecutionOutcome(
            spec = SubagentTaskSpec(taskName = "审查", role = "reviewer", prompt = "检查"),
            subSessionId = "subagent:reviewer:abc",
            isSuccess = false,
            summary = "超时",
            toolCallCount = 2,
            termination = SubagentTermination.TIMEOUT,
        )

        val rendered = renderSummaryMarkdown(listOf(outcome))

        assertTrue(rendered.contains("task_id"))
        assertTrue(rendered.contains("subagent:reviewer:abc"))
    }
}
