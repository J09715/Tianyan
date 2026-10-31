package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.effects.ToolRecoveryNotice

class ChatTimelineProjectionTest {
    private fun call(id: String, tool: HarnessTool = HarnessTool.BASE) = ToolCall(id, 10, tool, buildJsonObject {})
    private fun result(id: String, success: Boolean = true) = ToolResult("r-$id", 20, id, success, "output")
    private fun user(id: String) = UserMessage(id, 1, "request")

    @Test fun groupsOnlyConsecutiveToolsAndPreservesSpecialCardsAndRawIndexes() {
        val messages = listOf(user("u"), call("a"), result("a"), call("b"),
            AssistantText("text", 30, "progress"), call("sub", HarnessTool.SUBAGENT),
            call("surface", HarnessTool.RENDER_SURFACE), call("ask", HarnessTool.ASK_USER), call("c"))
        val items = projectChatTimeline(messages)
        assertEquals(listOf("u", "tool_steps_a", "text", "sub", "surface", "tool_steps_ask"), items.map { it.stableKey })
        val group = items[1] as ToolStepsItem
        assertEquals(listOf(1, 3), group.steps.map { it.stepRawIndex })
        assertTrue(group.containsMessage("b"))
        assertFalse(group.containsMessage("r-a"))
    }

    @Test fun streamingAppendKeepsGroupAnchorAndUserMessagesAlwaysSplitGroups() {
        val first = projectChatTimeline(listOf(call("a"))).single().stableKey
        val appended = projectChatTimeline(listOf(call("a"), result("a"), call("b")))
        assertEquals(first, appended.single().stableKey)
        val separate = projectChatTimeline(listOf(call("a"), user("u"), call("b")))
        assertEquals(2, separate.filterIsInstance<ToolStepsItem>().size)
    }

    @Test fun collapsedGroupsKeepRecentStepsAndAttentionAndCanRevealHistoryInBoundedBatches() {
        val steps = (1..40).map { ChatRenderItem.MessageItem(call("c$it"), it) }
        val results = steps.associate { it.message.id to result(it.message.id) }.toMutableMap()
        results["c3"] = result("c3", false)
        assertEquals(listOf("c3", "c39", "c40"), visibleToolChainSteps(steps, results, false, 2).mapNotNull { it.stepId })
        val expanded = visibleToolChainSteps(steps, results, false, 8)
        assertEquals(9, expanded.size)
        assertEquals(listOf(steps[2]) + steps.takeLast(8), expanded)
        assertEquals(steps, visibleToolChainSteps(steps, results, false, 40))
        assertTrue(visibleToolChainSteps(steps, results, false, 2, "c12").any { it.stepId == "c12" })
    }

    @Test fun approvalAndUnknownResultsNeverBecomeSuccessOrGenericFailure() {
        val failed = result("a", false)
        assertEquals(ToolStepState.WAITING, toolStepState(failed.copy(awaitingApproval = true), true))
        assertEquals(ToolStepState.DEFERRED, toolStepState(failed.copy(approvalDeferred = true), false))
        assertEquals(ToolStepState.UNKNOWN, toolStepState(failed.copy(errorCode = ToolRecoveryNotice.OUTCOME_UNKNOWN), false))
        assertEquals(ToolStepState.FAILED, toolStepState(failed, false))
        assertEquals(ToolStepState.SUCCEEDED, toolStepState(result("a"), false))
        assertEquals(ToolStepState.INCOMPLETE, toolStepState(null, false))
        assertEquals(ToolStepState.RUNNING, toolStepState(null, true))
    }

    @Test fun fullToolbarBelongsToFinalProseOfEachUserTurnAndWaitsForCompletion() {
        val messages = listOf(user("u1"), AssistantText("a1", 2, "progress"), call("tool"),
            AssistantText("a2", 3, "answer"), user("u2"), AssistantText("a3", 4, "working"))
        assertEquals(setOf("a2"), assistantToolbarIds(messages, true))
        assertEquals(setOf("a2", "a3"), assistantToolbarIds(messages, false))
    }

    @Test fun targetsNeverExposeCommandBodiesOrArbitraryArgumentObjects() {
        val command = call("cmd").copy(args = buildJsonObject { put("command", "SECRET-COMMAND"); put("token", "SECRET") })
        assertNull(toolStepTarget(command))
        val path = call("read", HarnessTool.READ).copy(args = buildJsonObject { put("path", "/workspace/project/file.kt") })
        assertEquals("file.kt", toolStepTarget(path))
        val invalid = path.copy(args = buildJsonObject { put("path", buildJsonObject { put("token", "SECRET") }) })
        assertNull(toolStepTarget(invalid))
    }

    @Test fun legacyRoundCollapseRemainsCompatibleWithTimelineGrouping() {
        val messages = listOf(user("u1")) + (1..6).map { call("c$it") } +
            listOf(AssistantText("answer", 50, "done"), user("u2"))
        val collapsed = projectChatTimeline(messages, collapseEnabled = true)
        assertEquals(1, collapsed.filterIsInstance<ChatRenderItem.CollapseButtonItem>().size)
        assertEquals(listOf("c5", "c6"), collapsed.filterIsInstance<ToolStepsItem>().single().steps.mapNotNull { it.stepId })
        val expanded = projectChatTimeline(messages, expandedOverrides = mapOf("u1" to true), collapseEnabled = true)
        assertEquals(6, expanded.filterIsInstance<ToolStepsItem>().single().steps.size)
    }

    @Test fun longRunsKeepOneChainAndBoundTheInitiallyVisibleTail() {
        val messages = (1..100).map { call("c$it") }
        val groups = projectChatTimeline(messages).filterIsInstance<ToolStepsItem>()
        assertEquals(1, groups.size)
        assertEquals(8, visibleToolChainSteps(groups.single().steps, emptyMap(), false).size)
        assertEquals(messages.map { it.id }, groups.flatMap { it.steps }.mapNotNull { it.stepId })
    }

    @Test fun assistantIdentityAppearsOncePerUserTurnAcrossTextAndSteps() {
        val items = projectChatTimeline(listOf(user("u1"), call("t1"), AssistantText("a1", 20, "done"),
            user("u2"), AssistantText("a2", 30, "progress"), call("t2")))
        assertEquals(setOf("tool_steps_t1", "a2"), assistantTurnLabelKeys(items))
    }

    @Test fun reasoningJoinsTheSameChainWithoutCountingAsToolSteps() {
        val messages = listOf(user("u"),
            AssistantText("a1", 2, "", reasoning = "先看环境"),
            call("t1"), AssistantText("a2", 3, "", reasoning = "再确认"), call("t2"))
        val chain = projectChatTimeline(messages).filterIsInstance<ToolStepsItem>().single()
        assertEquals(listOf("a1", "t1", "a2", "t2"), chain.steps.mapNotNull { it.stepId })
        assertEquals(2, chain.toolCount)
        assertEquals("tool_steps_t1", chain.stableKey)
    }

    @Test fun reasoningWithProseKeepsItsBubbleButStopsRenderingThinkingTwice() {
        val items = projectChatTimeline(listOf(user("u"), AssistantText("a", 2, "答案", reasoning = "推理")))
        assertEquals(listOf("u", "reasoning_a", "a"), items.map { it.stableKey })
        assertFalse((items[2] as ChatRenderItem.MessageItem).showReasoning)
    }

    @Test fun streamingReasoningPlaceholderDoesNotLeaveAnEmptyBubble() {
        val items = projectChatTimeline(listOf(user("u"), AssistantText("a", 2, "", reasoning = "思考中")))
        assertEquals(listOf("u", "reasoning_a"), items.map { it.stableKey })
    }

    @Test fun focusedMessageResolvesItsRoundEvenWhenLegacyCollapseHidesIt() {
        val messages = listOf(user("u1")) + (1..5).map { call("c$it") } + listOf(user("u2"))
        assertFalse(projectChatTimeline(messages, collapseEnabled = true).any { it.containsMessage("c1") })
        val round = focusedRoundKey(messages, "c1")!!
        assertEquals("u1", round)
        assertTrue(projectChatTimeline(messages, expandedOverrides = mapOf(round to true), collapseEnabled = true)
            .any { it.containsMessage("c1") })
        assertNull(focusedRoundKey(messages, "missing"))
    }
}
