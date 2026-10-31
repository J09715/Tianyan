package top.tianyan.app.harness.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.ApiToolCallSpec
import top.tianyan.app.harness.renderSummaryMarkdown
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.TextToolCallCodec
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import kotlinx.serialization.json.JsonPrimitive
import top.tianyan.app.core.model.SubagentTaskSpec
import top.tianyan.app.harness.SubagentOrchestrator

class SubagentLaneContractsTest {

    private val json = Json { isLenient = true }
    private val plain = TextToolCallCodec.normalize(Json { isLenient = true }, "分析完成，结论如下：A 依赖 B。")

    // ---------- 完成判定 ----------

    @Test
    fun `blank round text is never a completion`() {
        val verdict = judgeSubagentConclusion(
            roundText = "",
            structuredCalls = emptyList(),
            textNormalization = TextToolCallCodec.normalize(json, ""),
        )

        assertFalse(verdict.accepted)
        assertEquals(SubagentTermination.INCOMPLETE, verdict.termination)
        assertTrue(verdict.reason.contains("没有输出任何结论文本"))
    }

    @Test
    fun `text still carrying a tool protocol is never a completion`() {
        val textual = TextToolCallCodec.normalize(
            json,
            """先读文件[[tool_call]]{"name":"read","arguments":{"path":"a.kt"}}[[/tool_call]]""",
        )

        val verdict = judgeSubagentConclusion(textual.displayText, emptyList(), textual)

        assertFalse(verdict.accepted)
        assertEquals(SubagentTermination.INCOMPLETE, verdict.termination)
    }

    @Test
    fun `structured tool calls in the final round are not a completion`() {
        val verdict = judgeSubagentConclusion(
            roundText = "我再看一个文件",
            structuredCalls = listOf(ApiToolCallSpec("c1", "read", "{}")),
            textNormalization = plain,
        )

        assertFalse(verdict.accepted)
    }

    @Test
    fun `self declared incompletion is not a completion`() {
        listOf("报告尚未完成，缺少构建日志", "剩余文件未能完成审计", "该操作已交由主智能体处理")
            .forEach { text ->
                val verdict = judgeSubagentConclusion(text, emptyList(), TextToolCallCodec.normalize(json, text))
                assertFalse("应判为未完成：$text", verdict.accepted)
                assertEquals(SubagentTermination.INCOMPLETE, verdict.termination)
            }
    }

    /**
     * 只读审计任务的合法结论会大量引用"写入失败""需要审批"等现象描述词。
     * 判定必须靠结构化证据（blockedWrites / deferredApprovals / 写失败记账），
     * 不能按关键词把这类结论误判为未完成。
     */
    @Test
    fun `phenomenon wording in a read-only audit conclusion is still a completion`() {
        listOf(
            "排查结论：订单服务写入失败的根因是磁盘配额耗尽，建议扩容后重试。",
            "该接口无法写入缓存，因为上游返回 403；需要审批的运维操作已在结论中列出供决策。",
        ).forEach { text ->
            val verdict = judgeSubagentConclusion(text, emptyList(), TextToolCallCodec.normalize(json, text))
            assertTrue("审计结论不应被误判为未完成：$text", verdict.accepted)
        }
    }

    @Test
    fun `a write that ultimately failed blocks completion`() {
        val verdict = judgeSubagentConclusion(
            roundText = "报告已生成并写入。",
            structuredCalls = emptyList(),
            textNormalization = plain,
            unresolvedWriteFailures = listOf("write audit/report.md"),
        )

        assertFalse(verdict.accepted)
        assertEquals(SubagentTermination.WRITE_FAILED, verdict.termination)
        assertTrue(verdict.reason.contains("audit/report.md"))
    }

    @Test
    fun `deferred approvals outrank a confident sounding conclusion`() {
        val verdict = judgeSubagentConclusion(
            roundText = "全部处理完毕。",
            structuredCalls = emptyList(),
            textNormalization = plain,
            deferredApprovals = listOf(SubagentApprovalHandoff("write", """{"path":"a.kt"}""", "需要审批")),
        )

        assertFalse(verdict.accepted)
        assertEquals(SubagentTermination.NEEDS_APPROVAL, verdict.termination)
        assertTrue(verdict.reason.contains("write"))
    }

    @Test
    fun `blocked writes outrank a confident sounding conclusion`() {
        val verdict = judgeSubagentConclusion(
            roundText = "报告已生成。",
            structuredCalls = emptyList(),
            textNormalization = plain,
            blockedWrites = listOf("write audit/report.md"),
        )

        assertFalse(verdict.accepted)
        assertEquals(SubagentTermination.WRITE_SCOPE_BLOCKED, verdict.termination)
    }

    @Test
    fun `a real conclusion is accepted`() {
        val verdict = judgeSubagentConclusion("分析完成，结论如下：A 依赖 B。", emptyList(), plain)

        assertTrue(verdict.accepted)
        assertEquals(SubagentTermination.CONCLUDED, verdict.termination)
        assertEquals("", verdict.reason)
    }

    // ---------- 写租约强制校验 ----------

    @Test
    fun `read only task rejects every write tool`() {
        val args = buildJsonObject { put("path", "audit/report.md") }

        val rejection = subagentWriteScopeRejection(HarnessTool.WRITE, args, emptyList())

        assertNotNull(rejection)
        assertTrue(rejection!!.contains("未声明 write_paths"))
    }

    @Test
    fun `scoped lease allows the declared subtree and rejects the rest`() {
        val inside = buildJsonObject { put("path", "app/src/ui/Screen.kt") }
        val outside = buildJsonObject { put("path", "server/src/Main.kt") }

        assertNull(subagentWriteScopeRejection(HarnessTool.WRITE, inside, listOf("app/src/ui")))
        assertNotNull(subagentWriteScopeRejection(HarnessTool.EDIT, outside, listOf("app/src/ui")))
    }

    @Test
    fun `whole workspace lease allows any path and downloads honour destination`() {
        val anywhere = buildJsonObject { put("path", "server/src/Main.kt") }
        val download = buildJsonObject { put("destination", "assets/logo.png") }

        assertNull(subagentWriteScopeRejection(HarnessTool.WRITE, anywhere, listOf("*")))
        assertNotNull(subagentWriteScopeRejection(HarnessTool.DOWNLOAD, download, listOf("app/src")))
        assertNull(subagentWriteScopeRejection(HarnessTool.DOWNLOAD, download, listOf("assets")))
    }

    /**
     * 租约比较必须先按段消解 `..`：否则租约 `docs` 下 `docs/../secret.txt` 会因前缀匹配被放行。
     */
    @Test
    fun `dot dot segments cannot escape the declared lease`() {
        val escape = buildJsonObject { put("path", "docs/../secret.txt") }
        val stillInside = buildJsonObject { put("path", "docs/sub/../guide.md") }
        val aboveRoot = buildJsonObject { put("path", "../../etc/passwd") }

        assertNotNull(subagentWriteScopeRejection(HarnessTool.WRITE, escape, listOf("docs")))
        assertNull(subagentWriteScopeRejection(HarnessTool.WRITE, stillInside, listOf("docs")))
        assertTrue(
            subagentWriteScopeRejection(HarnessTool.WRITE, aboveRoot, listOf("docs")).orEmpty()
                .contains("逃出工作区顶层"),
        )
    }

    /** `.` 归一化后是空串，但它表示工作区根，等价于 `*`，不能退化成"未声明 → 只读"。 */
    @Test
    fun `dot lease is treated as the whole workspace`() {
        val anywhere = buildJsonObject { put("path", "server/src/Main.kt") }

        assertNull(subagentWriteScopeRejection(HarnessTool.WRITE, anywhere, listOf(".")))
    }

    @Test
    fun `mcp call and mutating build script actions require a lease`() {
        val mcpCall = buildJsonObject { put("action", "call") }
        val create = buildJsonObject { put("action", "create") }
        val update = buildJsonObject { put("action", "update") }
        val bind = buildJsonObject { put("action", "bind") }

        assertNotNull(subagentWriteScopeRejection(HarnessTool.MCP, mcpCall, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.MCP, mcpCall, listOf("scripts")))
        assertNotNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, create, emptyList()))
        assertNotNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, update, emptyList()))
        assertNotNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, bind, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, create, listOf("scripts")))
    }

    @Test
    fun `mcp metadata and build script reads stay available in read only lanes`() {
        assertNull(subagentWriteScopeRejection(HarnessTool.MCP, buildJsonObject { put("action", "list") }, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.MCP, buildJsonObject { put("action", "inspect") }, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, buildJsonObject { put("action", "list") }, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.BUILD_SCRIPT, buildJsonObject { put("action", "get") }, emptyList()))
    }

    @Test
    fun `read tools are never gated by the write lease`() {
        val args = buildJsonObject { put("path", "server/src/Main.kt") }

        assertNull(subagentWriteScopeRejection(HarnessTool.READ, args, emptyList()))
        assertNull(subagentWriteScopeRejection(HarnessTool.BASE, buildJsonObject { put("command", "ls") }, emptyList()))
    }

    @Test
    fun `write intent in the task text is detected so the missing lease can be surfaced`() {
        assertTrue(declaresWriteIntent("请把审计结论落盘到 audit/report.md"))
        assertTrue(declaresWriteIntent("生成文件并保存到工作区"))
        assertTrue(declaresWriteIntent("把结果写入 report.md"))
        assertFalse(declaresWriteIntent("请只读分析当前依赖关系并给出结论"))
    }

    /**
     * 命中写意图会向子任务注入"任务要落盘但没有写租约"的警示，误触发等于给纯分析任务
     * 塞误导性提示词。高频动词单独出现时不能算写意图。
     */
    @Test
    fun `analysis-only tasks do not trigger a false write intent`() {
        listOf(
            "请给出修改代码的建议，不要改动任何文件",
            "分析这个模块的写入路径设计是否合理",
            "总结排查结论并生成一份简要说明",
        ).forEach { prompt ->
            assertFalse("不应判为写意图：$prompt", declaresWriteIntent(prompt))
        }
    }

    // ---------- JSON_TEXT 协议适配 ----------

    @Test
    fun `json text mode converts tool exchanges into plain text turns`() {
        val call = ToolCall("call-1", 2L, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }, rawToolName = "read")
        val messages = listOf(
            UserMessage("task", 1L, "子任务"),
            call,
            ToolResult("res-1", 3L, "call-1", true, "文件内容"),
        )

        val out = isolatedProviderMessages(
            messages = messages,
            systemPrompt = "子智能体系统提示",
            forceFinalAnswer = false,
            toolCallMode = ToolCallMode.JSON_TEXT,
        )

        // 调用意图以模型自己的文本协议回放成 assistant 消息（落库 displayText 已剥离标记），
        // 结果以 user 文本回灌——模型必须能看到自己调用了什么参数才能关联结果。
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out.none { it.tool_calls != null })
        val replayedCall = out[2].content.orEmpty()
        assertTrue(replayedCall.contains("[[tool_call]]"))
        assertTrue(replayedCall.contains("\"read\""))
        assertTrue(replayedCall.contains("a.kt"))
        // 回放格式必须可被 normalize 完整还原
        val normalized = TextToolCallCodec.normalize(json, replayedCall)
        assertEquals(1, normalized.calls.size)
        assertEquals("read", normalized.calls.single().name)
        assertTrue(out.last().content.orEmpty().contains("【工具 read 执行结果·成功】"))
    }

    @Test
    fun `json text mode does not replay dangling tool calls`() {
        val dangling = ToolCall("call-1", 2L, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }, rawToolName = "read")
        val messages = listOf(
            UserMessage("task", 1L, "子任务"),
            dangling,
        )

        val out = isolatedProviderMessages(
            messages = messages,
            systemPrompt = "子智能体系统提示",
            forceFinalAnswer = false,
            toolCallMode = ToolCallMode.JSON_TEXT,
        )

        // 无结果的悬空调用不回放，与主会话 NATIVE 分支同口径
        assertEquals(listOf("system", "user"), out.map { it.role })
    }

    @Test
    fun `json text mode injects the marker protocol via the system prompt argument`() {
        val out = isolatedProviderMessages(
            messages = listOf(UserMessage("task", 1L, "子任务")),
            systemPrompt = "子智能体系统提示\n\n[[tool_call]] 协议说明",
            forceFinalAnswer = false,
            toolCallMode = ToolCallMode.JSON_TEXT,
        )

        assertTrue(out.first().content.orEmpty().contains("[[tool_call]]"))
    }

    @Test
    fun `native mode keeps the structured tool protocol`() {
        val call = ToolCall("call-1", 2L, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }, rawToolName = "read")
        val messages = listOf(
            UserMessage("task", 1L, "子任务"),
            call,
            ToolResult("res-1", 3L, "call-1", true, "文件内容"),
        )

        val out = isolatedProviderMessages(messages, "系统提示", forceFinalAnswer = false)

        assertEquals(listOf("system", "user", "assistant", "tool"), out.map { it.role })
        assertEquals("call-1", out[2].tool_calls?.single()?.id)
    }

    // ---------- 上下文预算治理 ----------

    @Test
    fun `history within budget is passed through untouched`() {
        val messages = laneHistory(resultChars = 100)

        assertEquals(messages, budgetedLaneMessages(messages, budgetTokens = 100_000))
    }

    @Test
    fun `oversized history is compacted without dropping protocol messages`() {
        val messages = laneHistory(resultChars = 40_000)

        val trimmed = budgetedLaneMessages(messages, budgetTokens = 500)

        // 消息条数与顺序必须保持不变：NATIVE 协议下丢掉调用/结果会产生非法 transcript。
        assertEquals(messages.size, trimmed.size)
        assertEquals(messages.map { it.id }, trimmed.map { it.id })
        val outputs = trimmed.filterIsInstance<ToolResult>().map { it.output }
        assertTrue(outputs.first().length < 40_000)
        // 最近一条结果享有远宽于旧结果的额度，但不能完全豁免：
        // 最后一轮读一个几百 KB 的大文件正是最需要兜底的场景。
        assertTrue(outputs.last().length > outputs.first().length)
        assertTrue(outputs.last().length < 40_000)
    }

    /** 保护额度之内的最近结果必须原样保留，不能被"顺手"截断。 */
    @Test
    fun `a recent result within the protected allowance is kept verbatim`() {
        val messages = laneHistory(resultChars = 3_000)

        val trimmed = budgetedLaneMessages(messages, budgetTokens = 500)

        assertEquals(3_000, trimmed.filterIsInstance<ToolResult>().last().output.length)
    }

    // ---------- 超时阶段成果 ----------

    @Test
    fun `timeout summary carries the evidence gathered before the deadline`() {
        val transcript = listOf(
            UserMessage("task", 1L, "子任务"),
            ToolCall("c1", 2L, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }, rawToolName = "read"),
            ToolResult("r1", 3L, "c1", true, "读取完成"),
            ToolCall("c2", 4L, HarnessTool.WRITE, buildJsonObject { put("path", "audit/report.md") }, rawToolName = "write"),
            ToolResult("r2", 5L, "c2", true, "已写入"),
            ToolCall(
                "c3",
                6L,
                HarnessTool.BASE,
                buildJsonObject { put("command", "./gradlew build\n./gradlew test") },
                rawToolName = "base",
            ),
            ToolResult("r3", 7L, "c3", false, "exit 1 · 编译失败"),
            AssistantText("a1", 8L, "已完成 2/5 个文件的审计"),
        )

        val summary = buildSubagentTimeoutSummary(900_000L, 3, transcript, "subagent:auditor:abc")

        assertTrue(summary.contains("执行超时"))
        assertTrue(summary.contains("已执行工具 3 次：成功 2 / 失败 1"))
        assertTrue(summary.contains("audit/report.md"))
        assertTrue(summary.contains("a.kt"))
        assertTrue(summary.contains("编译失败"))
        assertTrue(summary.contains("已完成 2/5 个文件的审计"))
        assertTrue(summary.contains("subagent:auditor:abc"))
    }

    @Test
    fun `timeout summary degrades gracefully when nothing was recorded`() {
        val summary = buildSubagentTimeoutSummary(900_000L, 0, emptyList(), "subagent:auditor:abc")

        assertTrue(summary.contains("执行超时"))
        assertFalse(summary.contains("阶段成果"))
    }

    private fun laneHistory(resultChars: Int) = listOf(
        UserMessage("task", 1L, "子任务"),
        ToolCall("c1", 2L, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }, rawToolName = "read"),
        ToolResult("r1", 3L, "c1", true, "x".repeat(resultChars)),
        ToolCall("c2", 4L, HarnessTool.READ, buildJsonObject { put("path", "b.kt") }, rawToolName = "read"),
        ToolResult("r2", 5L, "c2", true, "y".repeat(resultChars)),
        ToolCall("c3", 6L, HarnessTool.READ, buildJsonObject { put("path", "c.kt") }, rawToolName = "read"),
        ToolResult("r3", 7L, "c3", true, "z".repeat(resultChars)),
    )

    // ---------- 疑似 shell 写软检测 ----------

    private fun baseCall(id: String, command: String, success: Boolean = true) = listOf(
        ToolCall(id, 1L, HarnessTool.BASE, buildJsonObject { put("command", JsonPrimitive(command)) }, rawToolName = "base"),
        ToolResult("r$id", 2L, id, success = success, output = "ok"),
    )

    @Test
    fun `suspected shell writes are detected for narrow or readonly claims`() {
        val transcript = listOf(
            UserMessage("u1", 1L, "任务"),
        ) + baseCall("c1", "echo done | tee /tmp/x.txt") + baseCall("c2", "grep foo app/src/Main.kt") + baseCall("c3", "sed -i 's/a/b/' docs/g.md")

        val hits = detectSuspectedShellWrites(transcript, writePaths = listOf("docs/"))
        // tee 与 sed -i 命中；grep 只读不命中
        assertEquals(2, hits.size)
    }

    @Test
    fun `readonly lane shell writes are flagged`() {
        val transcript = baseCall("c1", "rm -rf build/") + baseCall("c2", "cat README.md")
        val hits = detectSuspectedShellWrites(transcript, writePaths = emptyList())
        assertEquals(1, hits.size)
        assertTrue(hits.single().contains("rm -rf"))
    }

    @Test
    fun `whole workspace claim skips shell write detection`() {
        val transcript = baseCall("c1", "echo x > out.txt")
        assertTrue(detectSuspectedShellWrites(transcript, writePaths = listOf("*")).isEmpty())
    }

    @Test
    fun `error-stream suppression and dev-null targets are not writes`() {
        val transcript = baseCall("c1", "gradle test 2>/dev/null") + baseCall("c2", "ls foo 2>&1 | head -5")
        assertTrue(detectSuspectedShellWrites(transcript, writePaths = emptyList()).isEmpty())
    }

    @Test
    fun `stdout redirect to a real file is a suspected write`() {
        val transcript = baseCall("c1", "echo hello > docs/note.md")
        val hits = detectSuspectedShellWrites(transcript, writePaths = emptyList())
        assertEquals(1, hits.size)
    }

    @Test
    fun `failed commands are not counted`() {
        val transcript = baseCall("c1", "rm -rf build/", success = false)
        assertTrue(detectSuspectedShellWrites(transcript, writePaths = emptyList()).isEmpty())
    }

    @Test
    fun `summary exposes suspected shell writes`() {
        val outcome = SubagentOrchestrator.SubagentExecutionOutcome(
            spec = SubagentTaskSpec(taskName = "审查", role = "reviewer", prompt = "检查"),
            subSessionId = "subagent:reviewer:abc",
            isSuccess = true,
            summary = "完成",
            toolCallCount = 1,
            suspectedShellWrites = listOf("echo x > out.txt"),
        )
        assertTrue(renderSummaryMarkdown(listOf(outcome)).contains("echo x > out.txt"))
    }
}
