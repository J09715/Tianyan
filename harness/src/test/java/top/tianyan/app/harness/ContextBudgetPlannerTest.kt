package top.tianyan.app.harness

import org.junit.Assert.*
import org.junit.Test

class ContextBudgetPlannerTest {
    private fun plan(active: Int = 0, recall: Map<String, String> = emptyMap(), failures: String = "", plugin: String = "") =
        ContextBudgetPlanner.plan(16_000, "system", "summary", active, 0, 0, 100, recall, failures, plugin)

    @Test fun `background cannot crowd out current task and source wrappers are never split`() {
        val recall = mapOf("user" to "<untrusted>" + "背景".repeat(10_000) + "</untrusted>")
        val result = plan(15_999, recall, "failure", "plugin")
        assertEquals("", result.recall["user"])
        assertEquals("", result.failureMemory)
        assertEquals("", result.pluginContext)
        assertTrue(result.allocations.filter { it.kind in setOf("system", "summary", "active_history") }
            .all { it.beforeTokens == it.allocatedTokens })
        assertTrue(recall.getValue("user").endsWith("</untrusted>"))
    }

    @Test fun `task failures and newest recall take priority over older recall and plugins`() {
        val block = "事实".repeat(1_500)
        val result = plan(recall = linkedMapOf("old" to block, "new" to block), failures = block, plugin = block)
        assertEquals(block, result.failureMemory)
        assertEquals(block, result.recall["new"])
        assertEquals("", result.recall["old"])
        assertEquals("", result.pluginContext)
        assertTrue(result.allocations.filter { it.kind in setOf("recall", "failure_memory", "plugin_context") }
            .sumOf { it.allocatedTokens } <= 4_000)
    }

    @Test fun `small blocks keep exact bytes and repeated plans are stable`() {
        val recall = linkedMapOf("old" to "<recall>one</recall>", "new" to "<recall>two</recall>")
        val result = plan(recall = recall, failures = "failure", plugin = "plugin")
        assertEquals(recall, result.recall)
        assertEquals(result, plan(recall = recall, failures = "failure", plugin = "plugin"))
    }
}
