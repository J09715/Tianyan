package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

class ContextWindowPolicyTest {
    @Test
    fun `generated image payload is omitted from provider context and token estimate`() {
        val payload = "A".repeat(1_000_000)
        val raw = "完成：![图](data:image/png;base64,$payload) 请继续"

        val sanitized = ContextWindowPolicy.assistantTextForContext(raw)

        assertTrue(sanitized.contains("生成了一张图片"))
        assertTrue(sanitized.contains("请继续"))
        assertFalse(sanitized.contains(payload.take(64)))
        assertTrue(ContextWindowPolicy.estimateTokens(sanitized) < 100)
    }
    @Test
    fun keepsRecentMessagesWithinBudget() {
        val messages = listOf(
            UserMessage("1", 1, "a".repeat(2_000)),
            AssistantText("2", 2, "b".repeat(2_000)),
            UserMessage("3", 3, "recent"),
        )

        // Budget must leave room after the input-fraction + output/schema reserves.
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10, foldingRatioPercent = 70)

        assertEquals(2, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `exhausted budget retains only a minimal recent turn instead of all history`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            AssistantText("old-assistant", 2, "old answer"),
            UserMessage("latest-user", 3, "latest request"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 4_000, systemTokens = 4_000)

        assertEquals(2, keepFrom)
    }

    @Test
    fun `exhausted budget does not orphan the only tool result`() {
        val call = ToolCall("call", 1, HarnessTool.BASE, buildJsonObject {})
        val messages = listOf(call, ToolResult("result", 2, "call", true, "ok"))

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)

        assertEquals(0, keepFrom)
    }

    @Test
    fun `full-width CJK punctuation is estimated like CJK characters`() {
        // 全角标点（U+FF00-FF5E：，！？：等）在中文上下文实际 ~1 token/字，
        // 曾落入 ASCII 标点桶被按 /2.8 估算，系统性低估
        val punct = "，。！？：；（）".repeat(10)
        val han = "汉字测试样例闭环".repeat(10)
        assertEquals(
            ContextWindowPolicy.estimateTokens(han),
            ContextWindowPolicy.estimateTokens(punct),
        )
    }

    @Test
    fun `oversized system prompt is bounded`() {
        val fitted = ContextWindowPolicy.fitSystemPrompt("x".repeat(100_000), budget = 8_000)

        assertTrue(fitted.length < 100_000)
        assertTrue(fitted.contains("系统提示因上下文预算受限已截断"))
        // 截断结果必须落在预算上限内（budget × 0.6，该预算下 MIN 512 不生效）
        assertTrue(
            "ASCII 截断后仍超预算：${ContextWindowPolicy.estimateTokens(fitted)}",
            ContextWindowPolicy.estimateTokens(fitted) <= (8_000 * 0.60).toInt(),
        )
    }

    @Test
    fun `oversized CJK system prompt is bounded by the real token estimate`() {
        val cjkPrompt = "系统规则行".repeat(4_000) // 20_000 CJK 字符 ≈ 11_111 tokens
        val budget = 8_000
        val fitted = ContextWindowPolicy.fitSystemPrompt(cjkPrompt, budget)
        val suffix = "\n\n[系统提示因上下文预算受限已截断；请优先遵守以上核心规则]"

        assertTrue(fitted.contains("系统提示因上下文预算受限已截断"))
        assertTrue(
            "中文截断后仍超预算：${ContextWindowPolicy.estimateTokens(fitted)}",
            ContextWindowPolicy.estimateTokens(fitted) <= (8_000 * 0.60).toInt(),
        )
        // 反证锚点：旧的固定 4 字符/token 截断在同样输入下必然超限——本测试修复前必红
        val naiveKeep = (8_000 * 0.60).toInt() * 4 - suffix.length
        assertTrue(
            "锚点失效：固定字符比截断在 CJK 下已不再超限，需复核口径",
            ContextWindowPolicy.estimateTokens(cjkPrompt.take(naiveKeep)) > (8_000 * 0.60).toInt(),
        )
    }

    @Test
    fun `giant last user message is truncated to fit the folding limit`() {
        val giantText = "文档内容行。".repeat(4_000) // 24_000 CJK 字符 ≈ 13_333 tokens
        val messages = listOf(
            UserMessage("u1", 1, "早前请求"),
            AssistantText("a1", 2, "早前回复"),
            UserMessage("giant", 3, giantText),
        )
        val limit = 4_000

        val folded = ContextWindowPolicy.truncateOversizedUserMessages(messages, limit)
        val foldedTotal = folded.sumOf { message ->
            when (message) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
                else -> 0
            }
        }

        assertTrue("截断后必须回到折叠线内：$foldedTotal", foldedTotal <= limit)
        val truncatedGiant = folded.last() as UserMessage
        assertTrue(truncatedGiant.text.contains("消息过长已截断"))
        assertTrue("头部保留", truncatedGiant.text.startsWith("文档内容行。"))
        assertTrue("尾部保留", truncatedGiant.text.endsWith("文档内容行。"))
        // 投影级截断：落库 transcript 与 UI 看到的原消息不动
        assertEquals(giantText, (messages.last() as UserMessage).text)
        // 预算充裕时整列表原样返回，不受影响
        assertEquals(messages, ContextWindowPolicy.truncateOversizedUserMessages(messages, limit = 100_000))
    }

    @Test
    fun compactsLongToolOutputWithHeadAndTail() {
        val compacted = ContextWindowPolicy.compactToolOutput(
            toolName = null,
            args = null,
            output = (1..10).joinToString("\n") { "line-$it" },
            success = true,
        )

        assertTrue(compacted.contains("line-1"))
        assertTrue(compacted.contains("line-10"))
        assertTrue(compacted.contains("已略去 5 行"))
    }

    @Test
    fun effectiveUsageReplacesCollapsedHistoryWithOneBoundedSummary() {
        val messages = buildList<HarnessMessage> {
            repeat(100) { index ->
                add(UserMessage("u-$index", index * 2L, "需求-$index " + "a".repeat(500)))
                add(AssistantText("a-$index", index * 2L + 1, "回复-$index " + "b".repeat(500)))
            }
            add(UserMessage("latest", 1_000, "最近问题"))
        }

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 18_000,
            systemTokens = 100,
            compactionEnabled = true,
        )

        assertTrue(usage.keepFromIndex > 0)
        assertTrue(usage.totalTokens < 18_000)
        assertTrue(usage.conversationTokens < messages.sumOf {
            when (it) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(it.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(it.text)
                else -> 0
            }
        })
    }

    @Test
    fun `large context models keep all history that fits the token budget`() {
        val messages = buildList<HarnessMessage> {
            repeat(30) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index"))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index"))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages = messages,
            budget = 1_000_000,
            systemTokens = 0,
        )

        assertEquals(0, keepFrom)
    }

    @Test
    fun `token boundary advances to the next complete user turn`() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val messages = listOf(
            UserMessage("old", 1, "x".repeat(1_000)),
            call,
            ToolResult("result", 3, "call", true, "ok"),
            UserMessage("latest", 4, "now"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10, foldingRatioPercent = 70)

        assertEquals(3, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `parallel tool calls and results remain paired across a forced boundary`() {
        val call1 = ToolCall(
            "call-1",
            2,
            HarnessTool.BASE,
            buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val call2 = ToolCall("call-2", 3, HarnessTool.READ, buildJsonObject {})
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            call1,
            call2,
            ToolResult("result-1", 4, "call-1", true, "first"),
            ToolResult("result-2", 5, "call-2", true, "second"),
            AssistantText("old-answer", 6, "done"),
            UserMessage("latest-user", 7, "latest request"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10, foldingRatioPercent = 70)
        val kept = messages.drop(keepFrom)
        val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }

        assertEquals(6, keepFrom)
        assertTrue(kept.first() is UserMessage)
        assertTrue(kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds })
    }

    @Test
    fun `interrupted cross-turn tool result pulls its call back into the window`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            ToolCall("call", 2, HarnessTool.BASE, buildJsonObject {}),
            UserMessage("latest-user", 3, "latest request"),
            ToolResult("late-result", 4, "call", true, "late"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)
        val kept = messages.drop(keepFrom)

        assertEquals(1, keepFrom)
        assertTrue(kept.first() is ToolCall)
        assertTrue(kept.filterIsInstance<ToolResult>().all { result ->
            kept.filterIsInstance<ToolCall>().any { it.id == result.toolCallId }
        })
    }

    @Test
    fun historySummaryKeepsStructuredFactsAndToolSpecificFailureContext() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            buildJsonObject { put("command", "./gradlew test") },
        )
        val summary = ContextWindowPolicy.buildHistorySummary(
            listOf(
                UserMessage("u", 1, "必须保持 core 模块纯 Kotlin，不能引入 Android 依赖"),
                AssistantText("a", 2, "决定采用滑动窗口方案，修改 /workspace/app/src/Main.kt"),
                call,
                ToolResult("r", 4, "call", false, "FAILURE: tests failed with a stack trace"),
            ),
        )

        assertTrue(summary.contains("用户硬约束"))
        assertTrue(summary.contains("关键决定"))
        assertTrue(summary.contains("涉及文件"))
        assertTrue(summary.contains("失败根因线索"))
        assertTrue(summary.contains("gradlew"))
    }

    @Test
    fun `estimateEffectiveUsage calculates 8-dimensional breakdown correctly`() {
        val messages = listOf(
            UserMessage("u1", 1, "Hello world"),
            AssistantText("a1", 2, "I will check the files."),
            ToolCall("t1", 3, HarnessTool.BASE, buildJsonObject {
                put("command", "ls -la")
            }),
            ToolResult("r1", 4, "t1", true, "file1.txt\nfile2.txt"),
        )

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 128_000,
            systemTokens = 5_000,
            compactionEnabled = true,
            systemPromptTokens = 576,
            toolDefinitionTokens = 3_600,
            rulesTokens = 1_400,
            skillsTokens = 500,
            mcpTokens = 800,
            subagentTokens = 1_100,
        )

        val bd = usage.breakdown
        assertEquals(576, bd.systemPromptTokens)
        assertEquals(3_600, bd.toolDefinitionTokens)
        assertEquals(1_400, bd.rulesTokens)
        assertEquals(500, bd.skillsTokens)
        assertEquals(800, bd.mcpTokens)
        assertEquals(1_100, bd.subagentTokens)
        assertEquals(0, bd.summarizedTokens)
        assertTrue(bd.conversationTokens > 0)
        assertEquals(bd.totalTokens, usage.totalTokens)
    }

    @Test
    fun `resolveBudget prefers the current model window then the fallback`() {
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000))
        assertEquals(1, ContextWindowPolicy.resolveBudget(0, 128_000))
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(128_000, 1_000_000))
    }

    @Test
    fun `mainstream model metadata adapts context window before global fallback`() {
        assertEquals(
            1_000_000,
            ContextWindowPolicy.resolveBudget(null, 128_000, modelId = "deepseek-v4-pro", providerId = "deepseek"),
        )
        assertEquals(1_048_576, ContextWindowPolicy.resolveContextWindow(null, "gemini-3-pro", "gemini"))
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000, modelId = "deepseek-chat", providerId = "deepseek"))
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000, modelId = "unknown-model", providerId = "unknown"))
        // 显式模型配置永远优先于自动适配。
        assertEquals(200_000, ContextWindowPolicy.resolveBudget(200_000, 128_000, modelId = "deepseek-v4-pro"))
    }

    @Test
    fun `oversized single turn is split inside the turn instead of kept whole`() {
        // 单个用户轮次自身超预算：最后一个用户轮次包含 10 条大 assistant 消息
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            add(AssistantText("a0", 2, "ok"))
            add(UserMessage("u1", 3, "huge task"))
            repeat(10) { index ->
                add(AssistantText("a-$index", 4L + index, "step $index " + "x".repeat(2_000)))
            }
        }

        // 预算必须高过固定预留（输出 8,192 + 工具 schema 预留）后仍有正的折叠线，
        // 否则只会走最小保留兜底，测不到轮内切割。
        val budget = 28_000
        val limit = ContextWindowPolicy.foldingLimitFor(budget, ratioPercent = 70, systemTokens = 10)
        assertTrue("fixture 预算必须让折叠线为正，limit=$limit", limit > 0)
        // 引擎至少保留最近两条；fixture 必须容纳它们，才能验证其余轮内消息被折叠。
        val mandatoryRecentTokens = messages.takeLast(2).filterIsInstance<AssistantText>()
            .sumOf { ContextWindowPolicy.estimateTokens(it.text) }
        assertTrue("fixture must fit the mandatory recent messages", limit >= mandatoryRecentTokens)
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = budget, systemTokens = 10, foldingRatioPercent = 70)

        // 旧行为会把整个巨型轮次保留（keepFrom == 2 起点且 kept 超限）；必须在轮内切
        assertTrue(keepFrom > 2)
        val firstKept = messages[keepFrom]
        assertTrue(
            "boundary must be user/assistant/tool_call, was $firstKept",
            firstKept is UserMessage || firstKept is AssistantText || firstKept is ToolCall,
        )
        val keptTokens = messages.drop(keepFrom).sumOf { message ->
            when (message) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
                is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString())
                is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
                else -> 0
            }
        }
        assertTrue("kept tokens $keptTokens must fit the limit $limit", keptTokens <= limit)
    }

    @Test
    fun `split turn boundary never separates a tool call from its result`() {
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            add(UserMessage("u1", 3, "huge task"))
            repeat(8) { index ->
                add(ToolCall("call-$index", 4L + index * 3, HarnessTool.BASE, buildJsonObject {}))
                add(ToolResult("result-$index", 5L + index * 3, "call-$index", true, "x".repeat(3_000)))
                add(AssistantText("note-$index", 6L + index * 3, "n".repeat(1_500)))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10, foldingRatioPercent = 70)
        val kept = messages.drop(keepFrom)

        if (keepFrom > 0) {
            val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }
            assertTrue(
                kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds },
            )
        }
    }

    @Test
    fun `keepRecentTokens override tightens the retained window`() {
        val messages = buildList<HarnessMessage> {
            repeat(100) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(300)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(300)))
            }
        }

        val base = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 18_000,
            systemTokens = 10,
            foldingRatioPercent = 100,
        )
        assertTrue(base > 0)

        val tightened = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 18_000,
            systemTokens = 10,
            keepRecentTokens = 400,
            foldingRatioPercent = 100,
        )

        assertTrue("tightened($tightened) should be beyond base($base)", tightened > base)
        val keptTokens = messages.drop(tightened).sumOf { message ->
            when (message) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
                else -> 0
            }
        }
        assertTrue("kept tokens $keptTokens should stay near the 400 cap", keptTokens < 1_600)
    }

    @Test
    fun `keepRecentTokens is inert while the budget still fits`() {
        val messages = buildList<HarnessMessage> {
            repeat(5) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index"))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index"))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
            keepRecentTokens = 100,
        )

        assertEquals(0, keepFrom)
    }

    @Test
    fun `per-model reserveTokens override raises the compaction threshold`() {
        val messages = buildList<HarnessMessage> {
            repeat(10) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(600)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(600)))
            }
        }

        val withDefaultReserve = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
        )
        assertEquals(0, withDefaultReserve)

        val withHugeReserve = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
            reserveTokens = 120_000,
        )
        assertTrue("withHugeReserve should trigger compaction, actual=$withHugeReserve", withHugeReserve > 0)
    }

    @Test
    fun `stale oversized tool results are compacted with a history read pointer`() {
        // 8 条属于上一轮的结果 + 3 条当前轮（"latest" 之后）的结果。
        // keepRecentResults=2：当前轮 3 条靠轮次语义保护（不是靠下限兜底）。
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            repeat(8) { index ->
                val callId = "call-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.MCP, buildJsonObject {}))
                add(ToolResult("result-$index", 3L + index, callId, true, "{\"refs\":[${"e$index,".repeat(100)}]}"))
            }
            add(UserMessage("latest", 20, "now"))
            repeat(3) { index ->
                val callId = "current-call-$index"
                add(ToolCall(callId, 21L + index, HarnessTool.MCP, buildJsonObject {}))
                add(ToolResult("current-result-$index", 22L + index, callId, true, "{\"refs\":[${"c$index,".repeat(100)}]}"))
            }
        }
        val details = messages.filterIsInstance<ToolCall>().associate {
            it.id to ("mcp__browser__snapshot" to it.args)
        }
        fun resultIndexOf(id: String) = messages.indexOfFirst { it.id == id }

        val truncated = ContextWindowPolicy.truncateStaleToolResults(messages, details, keepRecentResults = 2)

        // 条数与顺序不变：NATIVE 协议下丢消息会产生非法 transcript
        assertEquals(messages.size, truncated.size)
        assertEquals(messages.map { it.id }, truncated.map { it.id })
        // 当前轮的 3 条结果原样保留（轮次保护，超出 floor=2 的部分也保留）
        (0..2).forEach { index ->
            val id = "current-result-$index"
            assertEquals(
                (messages[resultIndexOf(id)] as ToolResult).output,
                (truncated[resultIndexOf(id)] as ToolResult).output,
            )
        }
        // 上一轮 8 条全部超过阈值且不受保护，压缩并带 history_read 指针
        (0..7).forEach { index ->
            val id = "result-$index"
            val at = resultIndexOf(id)
            val original = (messages[at] as ToolResult).output
            val output = (truncated[at] as ToolResult).output
            assertTrue("$id should be compacted", output.length < original.length)
            assertTrue(output.contains("history_read(message_id=\"$id"))
        }
    }

    @Test
    fun `stale results below the tool threshold stay verbatim`() {
        val messages = listOf(
            ToolCall("call-read", 1, HarnessTool.READ, buildJsonObject {}),
            ToolResult("short", 2, "call-read", true, "line\n".repeat(5)),
            ToolResult("also-short", 3, "call-read", true, "y".repeat(200)),
        )
        val details = mapOf(
            "call-read" to ("read" to (messages[0] as ToolCall).args),
        )

        val truncated = ContextWindowPolicy.truncateStaleToolResults(messages, details, keepRecentResults = 0)

        // 均低于 read 阈值（800），原样保留且返回同一实例（避免无谓复制）
        assertEquals(messages, truncated)
        assertTrue(truncated === messages)
    }

    // ------------------------------------------------------------------
    // 单一真相源回归：设置页「折叠线预览」必须与实际生效折叠线同源
    // ------------------------------------------------------------------

    @Test
    fun `declared model tokens win over global budget fallback`() {
        // 有模型声明 → 以声明值为准
        assertEquals(1_000_000, ContextWindowPolicy.resolveBudget(1_000_000, 250_938))
        assertEquals(250_938, ContextWindowPolicy.resolveBudget(250_938, 128_000))
        // 无声明 → 回退默认预算
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000))
    }

    @Test
    fun `effective budget is clamped to sane bounds`() {
        // 下界：小于 1 的值被抬到 1（resolveBudget 内部 coerceAtLeast(1)）
        assertEquals(1, ContextWindowPolicy.clampedBudget(0, 0))
        // 上界：超过 MAX_CONTEXT_BUDGET 的声明被钳到上限
        assertEquals(ContextWindowPolicy.MAX_CONTEXT_BUDGET, ContextWindowPolicy.clampedBudget(3_000_000, 128_000))
        // 上限内的正常值原样通过
        assertEquals(150_000, ContextWindowPolicy.clampedBudget(150_000, 128_000))
    }

    @Test
    fun `settings preview folding line matches engine for declared-model scenario`() {
        // 真实场景：模型档案 contextTokens 优先于全局预算；两侧必须走 clampedBudget + 同一套预留。
        val declared = 150_000
        val globalBudget = 80_000
        val ratio = 40
        val systemTokens = ContextWindowPolicy.DEFAULT_SYSTEM_PROMPT_TOKENS

        val engineBudget = ContextWindowPolicy.clampedBudget(declared, globalBudget)
        val previewBudget = ContextWindowPolicy.clampedBudget(declared, globalBudget)
        val engineLine = ContextWindowPolicy.foldingLimitFor(engineBudget, ratio, systemTokens)
        val previewLine = ContextWindowPolicy.foldingLimitFor(previewBudget, ratio, systemTokens)

        assertEquals(declared, engineBudget)
        assertEquals(engineLine, previewLine)

        val ratioScaled = engineBudget * ratio / 100
        val expected = ratioScaled - systemTokens -
            ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
            ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS
        assertEquals(expected, engineLine)

        val withoutReserves = ratioScaled
        assertTrue(
            "漏扣 system/输出/工具 schema 会让预览高于真实折叠线",
            withoutReserves > engineLine,
        )

        // 反证：若错误地拿被模型声明覆盖掉的全局预算当分母，折叠线会明显偏低。
        val buggyLine = ContextWindowPolicy.foldingLimitFor(globalBudget, ratio, systemTokens)
        assertTrue(
            "修复前的算法必须与真实折叠线不符，否则本测试无判别力",
            buggyLine != engineLine,
        )
    }

    @Test
    fun `preview and engine agree when no model declares context tokens`() {
        val globalBudget = 180_000
        val noDeclared: Int? = null
        val engineBudget = ContextWindowPolicy.clampedBudget(noDeclared, globalBudget)
        val previewBudget = ContextWindowPolicy.clampedBudget(noDeclared, globalBudget)

        assertEquals(globalBudget, engineBudget)
        assertEquals(engineBudget, previewBudget)
        assertEquals(
            ContextWindowPolicy.foldingLimitFor(engineBudget, 40, systemTokens = 500),
            ContextWindowPolicy.foldingLimitFor(previewBudget, 40, systemTokens = 500),
        )
    }

    @Test
    fun `oversized declared window is clamped before folding preview`() {
        val engineBudget = ContextWindowPolicy.clampedBudget(2_000_000, 250_938)
        val previewBudget = ContextWindowPolicy.clampedBudget(2_000_000, 250_938)
        assertEquals(ContextWindowPolicy.MAX_CONTEXT_BUDGET, engineBudget)
        assertEquals(engineBudget, previewBudget)
        assertEquals(
            ContextWindowPolicy.foldingLimitFor(engineBudget, 40, systemTokens = 0),
            ContextWindowPolicy.foldingLimitFor(previewBudget, 40, systemTokens = 0),
        )
    }

    @Test
    fun `foldingLimitFor subtracts system reserve and schema like the engine`() {
        // budget 需满足：折叠线 = budget*ratio - systemTokens - OUTPUT_RESERVE - SCHEMA_RESERVE
        // 放得下最新一两轮历史、又放不下全部 24 对消息（保证 keepFrom > 0）。
        // SCHEMA_RESERVE 抬至 5,900 后 18k 样本的折叠线仅剩 108（低于最小保留单元），故用 20k。
        val budget = 20_000
        val ratio = 80
        val systemTokens = 200
        val limit = ContextWindowPolicy.foldingLimitFor(budget, ratio, systemTokens)
        val scaledBudget = budget * ratio / 100
        assertEquals(
            scaledBudget - systemTokens -
                ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
                ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            limit,
        )
        assertTrue(limit > 0)

        val messages = buildList<HarnessMessage> {
            repeat(24) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(400)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(400)))
            }
        }
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = budget,
            systemTokens = systemTokens,
            foldingRatioPercent = ratio,
        )
        assertTrue(keepFrom > 0)
        val kept = messages.drop(keepFrom).sumOf(::estimateMessageTokens)
        assertTrue("kept=$kept should stay within fold line $limit", kept <= limit)
    }

    @Test
    fun `tool schema reserve follows the call mode`() {
        // 纯聊天 / 工具禁用：provider 面没有 tools，预留为 0（这段预算留给历史）
        assertEquals(0, ContextWindowPolicy.toolSchemaReserveTokensFor(pureChat = true, toolDisabled = true))
        assertEquals(0, ContextWindowPolicy.toolSchemaReserveTokensFor(pureChat = false, toolDisabled = true))
        // NATIVE（独立 tools 数组）与 JSON_TEXT（schema 注入 system）都要预留
        assertEquals(
            ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            ContextWindowPolicy.toolSchemaReserveTokensFor(pureChat = false, toolDisabled = false),
        )

        // 折叠线随之放宽：同一预算下，不外发 tools 的触发线正好高出整个 schema 预留
        val budget = 128_000
        val withTools = ContextWindowPolicy.foldingLimitFor(budget, 100, systemTokens = 0)
        val withoutTools = ContextWindowPolicy.foldingLimitFor(
            budget,
            100,
            systemTokens = 0,
            toolSchemaReserveTokens = ContextWindowPolicy.toolSchemaReserveTokensFor(
                pureChat = false,
                toolDisabled = true,
            ),
        )
        assertEquals(ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS, withoutTools - withTools)
    }

    @Test
    fun `saved ratio 100 still leaves estimation headroom on long contexts`() {
        val budget = 128_000
        val systemTokens = 10
        val expected = budget * 90 / 100 - systemTokens -
            ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
            ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS
        assertEquals(expected, ContextWindowPolicy.foldingLimitFor(budget, 100, systemTokens))
    }

    @Test
    fun `default ratio is 100 and folds later than 70 percent`() {
        val budget = 200_000
        val atDefault = ContextWindowPolicy.foldingLimitFor(
            budget,
            ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT,
            systemTokens = 0,
        )
        val at70 = ContextWindowPolicy.foldingLimitFor(budget, 70, systemTokens = 0)
        assertEquals(100, ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT)
        assertTrue("默认 100% 应比 70% 更充分使用模型窗口：$atDefault vs $at70", atDefault > at70)
        assertEquals((budget * 70 / 100) - ContextWindowPolicy.RESERVED_OUTPUT_TOKENS - ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS, at70)
    }

    @Test
    fun `flagship 200k window reserves estimation headroom in addition to output`() {
        val limit = ContextWindowPolicy.foldingLimitFor(200_000, 100, systemTokens = 0)
        assertEquals(
            180_000 - ContextWindowPolicy.RESERVED_OUTPUT_TOKENS - ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            limit,
        )
        assertTrue("200k 模型折叠线应超过 96k 旧封顶，实际=$limit", limit > 96_000)
    }

    @Test
    fun `panel denominator is model window while compaction threshold stays lower`() {
        val declared = 1_000_000
        val budget = ContextWindowPolicy.clampedBudget(declared, 128_000)
        val threshold = ContextWindowPolicy.foldingLimitFor(budget, 100, systemTokens = 1_000)
        assertEquals(declared, budget)
        assertTrue(threshold < budget)
        assertEquals(
            budget * 90 / 100 - 1_000 - ContextWindowPolicy.RESERVED_OUTPUT_TOKENS - ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            threshold,
        )
    }

    @Test
    fun `byte budget truncates current-round tool results before they blow the payload`() {
        val huge = "错误日志\n".repeat(80_000)
        val messages = listOf(
            UserMessage("u1", 1, "请排查"),
            ToolCall("c1", 2, HarnessTool.BASE, buildJsonObject {}),
            ToolResult("r1", 3, "c1", true, huge),
        )
        assertTrue(ContextWindowPolicy.estimateHarnessPayloadBytes(messages) > 200_000)
        val shrunk = ContextWindowPolicy.enforceRequestByteBudget(messages, maxBytes = 32_000)
        val result = shrunk.filterIsInstance<ToolResult>().single()
        assertTrue(result.output.length < huge.length)
        assertTrue(result.output.contains("请求体体积限制"))
        assertTrue(result.output.contains("history_read(message_id=\"r1\")"))
        assertTrue(ContextWindowPolicy.estimateHarnessPayloadBytes(shrunk) <= 32_000)
    }

    @Test
    fun `byte budget strips images when text compaction is not enough`() {
        val image = "data:image/png;base64," + "A".repeat(80_000)
        val messages = listOf(
            UserMessage("u1", 1, "看图", imageUrls = listOf(image)),
            AssistantText("a1", 2, "ok"),
        )
        val shrunk = ContextWindowPolicy.enforceRequestByteBudget(messages, maxBytes = 8_000)
        val user = shrunk.filterIsInstance<UserMessage>().single()
        assertTrue(user.imageUrls.isEmpty())
        assertTrue(user.text.contains("请求体体积限制"))
        assertTrue(ContextWindowPolicy.estimateHarnessPayloadBytes(shrunk) <= 8_000)
    }

    @Test
    fun `byte budget is a no-op when payload already fits`() {
        val messages = listOf(
            UserMessage("u1", 1, "hello"),
            AssistantText("a1", 2, "hi"),
        )
        val same = ContextWindowPolicy.enforceRequestByteBudget(messages, maxBytes = 64_000)
        assertTrue(same === messages)
    }

    // ------------------------------------------------------------------
    // 单一真相源回归：用量面板「已用量」必须基于引擎同口径投影（含老工具结果截断）
    // ------------------------------------------------------------------

    @Test
    fun `projectForUsage compacts stale tool results like the engine`() {
        // 构造：1 条老用户轮 + 8 条超大工具结果（不受保护）+ 1 条最新用户轮 + 3 条当前轮结果
        val big = "x".repeat(5_000)
        val messages = buildList<HarnessMessage> {
            add(UserMessage("old", 1, "old request"))
            repeat(8) { index ->
                val callId = "call-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.BASE, buildJsonObject {}))
                add(ToolResult("result-$index", 3L + index, callId, true, big))
            }
            add(UserMessage("latest", 20, "now"))
            repeat(3) { index ->
                val callId = "current-$index"
                add(ToolCall(callId, 21L + index, HarnessTool.BASE, buildJsonObject {}))
                add(ToolResult("current-result-$index", 22L + index, callId, true, big))
            }
        }

        val projected = ContextWindowPolicy.projectForUsage(messages, compactionEnabled = true)

        // 条数与顺序必须不变（NATIVE 协议丢消息会产生非法 transcript）
        assertEquals(messages.size, projected.size)
        assertEquals(messages.map { it.id }, projected.map { it.id })
        // 老轮次结果：最近 4 条 ToolResult 受保护（含 result-7），其余应被投影截断
        val oldResults = projected.filterIsInstance<ToolResult>()
            .filter { it.id.matches(Regex("result-\\d+")) }
        val protectedOld = oldResults.last().id           // takeLast(4) 保护了它
        val compactedOld = oldResults.filter { it.id != protectedOld }
        assertTrue("应存在被压缩的老轮次结果", compactedOld.isNotEmpty())
        compactedOld.forEach {
            assertTrue("${it.id} 应被压缩", it.output.length < big.length)
            assertTrue("${it.id} 应带 history_read 指针", it.output.contains("history_read(message_id="))
        }
        // 受保护的那条原样保留
        assertEquals(big.length, oldResults.last().output.length)
        // 当前轮结果原样保留
        projected.filterIsInstance<ToolResult>()
            .filter { it.id.startsWith("current-result-") }
            .forEach { assertEquals(big.length, it.output.length) }

        // 关闭压缩时完全不动（用户要原始历史）
        val untouched = ContextWindowPolicy.projectForUsage(messages, compactionEnabled = false)
        assertEquals(messages, untouched)
    }

    @Test
    fun `usage after projection is far below unprojected usage`() {
        // 反证锚点：不投影会显著虚高 —— 这正是「面板 457.8K vs 实际发送 141K」的缺陷。
        val big = "y".repeat(6_000)
        val messages = buildList<HarnessMessage> {
            add(UserMessage("old", 1, "old"))
            repeat(12) { index ->
                val callId = "c-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.BASE, buildJsonObject {}))
                add(ToolResult("r-$index", 3L + index, callId, true, big))
            }
            add(UserMessage("latest", 50, "now"))
        }
        val systemTokens = 5_000
        // 大预算 → 不触发整段折叠（keepFrom == 0），差异只来自工具结果截断，便于单独观察。
        val hugeBudget = 10_000_000

        fun usageOf(msgs: List<HarnessMessage>) = ContextWindowPolicy.estimateEffectiveUsage(
            messages = msgs,
            budget = hugeBudget,
            systemTokens = systemTokens,
            compactionEnabled = true,
            systemPromptTokens = systemTokens,
        )

        val unprojected = usageOf(messages).totalTokens
        val projected = usageOf(ContextWindowPolicy.projectForUsage(messages, compactionEnabled = true)).totalTokens

        assertTrue("投影后用量应明显低于未投影（$projected vs $unprojected）", projected < unprojected)
        // 反证锚点：不投影时 old 结果全文计入，投影后应至少省下一半以上
        assertTrue(
            "两者差距必须显著，否则本测试无判别力（$projected vs $unprojected）",
            unprojected - projected > unprojected / 2,
        )
    }

    @Test
    fun `tool schema reserve covers the real provider-visible schema`() {
        // 折叠线用常量 TOOL_SCHEMA_RESERVE_TOKENS 支付 tools schema；常量低于实测值时，
        // 缺口会直接从余量里挖走（真实输入超出折叠线 → 400/413）。加工具后本测试会立刻失败。
        val actual = ContextWindowPolicy.estimateToolDefinitionTokens(ProviderClient.TOOLS)
        val reserve = ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS
        assertTrue(
            "TOOL_SCHEMA_RESERVE_TOKENS=$reserve 低于实测 schema=$actual（${ProviderClient.TOOLS.size} 个工具），需同步抬升",
            actual <= reserve,
        )
    }

    @Test
    fun `retainLatestImages drops older pictures and keeps the newest`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "第一张", imageUrls = listOf("data:image/png;base64,AAA")),
            ApiMessage(role = "user", content = "第二张", imageUrls = listOf("data:image/png;base64,BBB")),
            ApiMessage(role = "user", content = "只要文字"),
        )
        val kept = retainLatestImages(messages, keep = 1)
        assertEquals(emptyList<String>(), kept[0].imageUrls)
        assertTrue(kept[0].content.orEmpty().contains("历史图片已省略"))
        assertEquals(listOf("data:image/png;base64,BBB"), kept[1].imageUrls)
        assertEquals("只要文字", kept[2].content)
    }

    private fun estimateMessageTokens(message: HarnessMessage): Int = when (message) {
        is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
        is AssistantText -> ContextWindowPolicy.estimateTokens(message.text) +
            ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString()) +
            ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
        else -> 0
    }
}
