package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import top.tianyan.app.harness.session.ConversationBranch
import top.tianyan.app.harness.session.ConversationBranchKind

class BranchBrowserProjectionTest {

    @Test
    fun `named lane keeps stable UI key when leaf advances`() {
        val before = branch(id = "subagent:explore:run@entry-1", laneName = "subagent:explore:run")
        val after = branch(id = "subagent:explore:run@entry-2", laneName = "subagent:explore:run")

        assertNotEquals(before.id, after.id)
        assertEquals(branchUiKey(before), branchUiKey(after))
    }

    @Test
    fun `subagent order ignores activity timestamp changes`() {
        val firstProjection = listOf(
            branch(id = "beta@entry-2", laneName = "subagent:beta:run", updatedAt = 200L),
            branch(id = "alpha@entry-1", laneName = "subagent:alpha:run", updatedAt = 100L),
        )
        val secondProjection = listOf(
            branch(id = "alpha@entry-3", laneName = "subagent:alpha:run", updatedAt = 300L),
            branch(id = "beta@entry-2", laneName = "subagent:beta:run", updatedAt = 200L),
        )

        assertEquals(
            stableSubagentBranches(firstProjection).map(::branchUiKey),
            stableSubagentBranches(secondProjection).map(::branchUiKey),
        )
    }

    @Test
    fun `non-subagent branches keep distinct version keys`() {
        val first = branch(
            id = "branch:review@entry-1",
            laneName = "branch:review",
            kind = ConversationBranchKind.BRANCH,
        )
        val second = branch(
            id = "branch:review@entry-2",
            laneName = "branch:review",
            kind = ConversationBranchKind.BRANCH,
        )

        assertNotEquals(branchUiKey(first), branchUiKey(second))
    }

    @Test
    fun `unnamed history branches keep distinct UI keys`() {
        val first = branch(
            id = "leaf:entry-1",
            laneName = null,
            kind = ConversationBranchKind.HISTORY,
        )
        val second = branch(
            id = "leaf:entry-2",
            laneName = null,
            kind = ConversationBranchKind.HISTORY,
        )

        assertEquals("leaf:entry-1", branchUiKey(first))
        assertEquals("leaf:entry-2", branchUiKey(second))
        assertNotEquals(branchUiKey(first), branchUiKey(second))
    }

    private fun branch(
        id: String,
        laneName: String?,
        updatedAt: Long = 0L,
        kind: ConversationBranchKind = ConversationBranchKind.SUBAGENT,
    ) = ConversationBranch(
        id = id,
        name = laneName ?: id,
        laneName = laneName,
        leafId = id.substringAfterLast('@'),
        depth = 1,
        preview = "",
        updatedAt = updatedAt,
        kind = kind,
        isCurrent = false,
        isBusy = false,
        faulted = false,
        toolCallCount = 0,
    )
}
