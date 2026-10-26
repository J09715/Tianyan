package top.tianyan.app.harness

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.SubagentTaskSpec
import top.tianyan.app.core.common.result.AppResult

internal class SubagentSummaryPaginationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun outcome(
        taskName: String,
        summary: String,
        subSessionId: String = "subagent:coder:$taskName",
    ) = SubagentOrchestrator.SubagentExecutionOutcome(
        spec = SubagentTaskSpec(taskName = taskName, prompt = "p", role = "coder"),
        subSessionId = subSessionId,
        isSuccess = true,
        summary = summary,
        toolCallCount = 3,
    )

    @Test
    fun `small summaries are injected in full without spill files`() = runBlocking {
        val root = temporaryFolder.newFolder("ws")
        val small = outcome("小任务", "结果一句话")
        val rendered = paginateSubagentSummary(listOf(small), "/ws/demo", WorkspaceFileAccess(root))

        assertEquals(renderSummaryMarkdown(listOf(small)), rendered)
        assertFalse(File(root, ".tianyan-subagent").isDirectory)
    }

    @Test
    fun `oversized summaries are truncated and spilled to workspace for paged read`() = runBlocking {
        val root = temporaryFolder.newFolder("ws")
        val longSummary = buildString { repeat(20_000) { append('x') } }
        val big = outcome("大任务", longSummary, subSessionId = "subagent:coder:abc123")
        val rendered = paginateSubagentSummary(listOf(big), "/ws/demo", WorkspaceFileAccess(root))

        // 截断 + 提示分页路径
        assertTrue("rendered=${rendered.take(300)}", rendered.contains("超出注入预算"))
        assertTrue("rendered=${rendered.take(300)}", rendered.contains(".tianyan-subagent/subagent-coder-abc123.md"))
        assertFalse(rendered.contains(longSummary))
        // 完整结果已落盘且与原输出一致
        val spillFile = File(root, ".tianyan-subagent/subagent-coder-abc123.md")
        assertTrue(spillFile.isFile)
        assertEquals(longSummary, spillFile.readText())
    }

    /**
     * 回归：落盘基准必须与 ToolExecutor 的 read 一致。
     * 汇总里给父智能体的是工作区相对路径，两边基准不同就会"提示有完整报告、实际读不到"。
     */
    @Test
    fun `spill lands where the workspace scoped read tool looks for it`() = runBlocking {
        val root = temporaryFolder.newFolder("ws")
        val workspace = "/workspace/proj"
        val longSummary = buildString { repeat(20_000) { append('x') } }
        val big = outcome("大任务", longSummary, subSessionId = "subagent:coder:abc123")
        val scoped = WorkspaceFileAccess(root).withBase(workspace)

        val rendered = paginateSubagentSummary(listOf(big), workspace, scoped)

        val relativePath = ".tianyan-subagent/subagent-coder-abc123.md"
        assertTrue(rendered.contains(relativePath))
        // read 用同一个 withBase 基准解析同一相对路径，必须命中刚写出的文件。
        val readBack = scoped.read(relativePath, null, null)
        assertTrue(
            "read 应能取回落盘内容，实际=$readBack",
            readBack is AppResult.Success,
        )
    }

    @Test
    fun `blank workspace degrades to plain truncation without spilling`() = runBlocking {
        val root = temporaryFolder.newFolder("ws")
        val longSummary = buildString { repeat(20000) { append('y') } }
        val rendered = paginateSubagentSummary(listOf(outcome("无工作区", longSummary)), "", WorkspaceFileAccess(root))

        // workspace 为空时不落盘，走全量注入（无 read 分页可用）
        assertEquals(renderSummaryMarkdown(listOf(outcome("无工作区", longSummary))), rendered)
        assertFalse(File(root, ".tianyan-subagent").isDirectory)
    }
}
