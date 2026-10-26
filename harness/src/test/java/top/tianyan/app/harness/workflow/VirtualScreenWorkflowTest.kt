package top.tianyan.app.harness.workflow

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskRegistry
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskState

class VirtualScreenWorkflowTest {
    private val context = WorkflowRuntimeContext("run-a", "wf", "/workspace", mapOf("TEXT_1" to "  中文\n内容  ", "X" to "500"))

    @Test
    fun `replay isolates runs preserves text and permits zero wait`() {
        val node = WorkflowNode("input", WorkflowNodeType.HOST_ACTION, "输入", config = mapOf(
            "action" to "virtual_screen_set_text", "text" to "\${TEXT_1}", "wait_ms" to "0",
        ))
        val args = virtualWorkflowArguments(node, context)
        assertEquals("  中文\n内容  ", args["text"]!!.jsonPrimitive.content)
        assertEquals("workflow-run-a", args["session"]!!.jsonPrimitive.content)
        val other = virtualWorkflowArguments(node, context.copy(executionId = "run-b"))
        assertNotEquals(args["session"], other["session"])
        val empty = virtualWorkflowArguments(node.copy(config = node.config + ("text" to "")), context)
        assertEquals("", empty["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid interpolated coordinates fail before input`() {
        val node = WorkflowNode("click", WorkflowNodeType.HOST_ACTION, "点击", config = mapOf(
            "action" to "virtual_screen_click", "x" to "\${X}", "y" to "1000",
        ))
        assertEquals("500", virtualWorkflowArguments(node, context)["x"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            virtualWorkflowArguments(node, context.copy(globalVariables = mapOf("X" to "-1")))
        }
    }

    @Test
    fun `changing action ignores fields left over from a wait node`() {
        val node = WorkflowNode("click", WorkflowNodeType.HOST_ACTION, "点击", config = mapOf(
            "action" to "virtual_screen_click", "x" to "500", "y" to "1000", "duration_ms" to "0",
        ))
        assertTrue(VirtualScreenWorkflowValidation.issues(node).isEmpty())
        assertFalse("duration_ms" in virtualWorkflowArguments(node, context))
    }

    @Test
    fun `HUD ownership persists between nodes and stop cancels the whole run`() = runTest {
        val registry = PhoneTaskRegistry()
        val runs = VirtualScreenWorkflowRuns(registry)
        val job = Job()
        runs.begin("run-a", job)
        val first = runs.screen("run-a", "screen")
        assertSame(first, runs.screen("run-a", "screen"))
        registry.pause("screen")
        assertEquals(PhoneTaskState.PAUSED, first.control.state.value)
        first.control.resume()
        assertEquals(0L, first.control.manualRevision())
        registry.pause("screen", manual = true)
        first.control.resume()
        assertEquals(1L, first.control.manualRevision())
        registry.cancel("screen")
        assertTrue(job.isCancelled)
        runs.end("run-a", PhoneTaskState.CANCELLED)
        assertNull(registry.get("screen"))
    }

    @Test
    fun `two runs cannot share a screen and closing does not cancel owner`() {
        val registry = PhoneTaskRegistry()
        val runs = VirtualScreenWorkflowRuns(registry)
        val first = Job()
        val second = Job()
        runs.begin("a", first); runs.begin("b", second)
        runs.screen("a", "shared")
        assertThrows(IllegalStateException::class.java) { runs.screen("b", "shared") }
        runs.beforeClose("a", "shared")
        assertTrue(first.isActive)
        assertNull(registry.get("shared"))
        runs.screen("b", "shared")
        runs.end("a", PhoneTaskState.COMPLETED); runs.end("b", PhoneTaskState.COMPLETED)
        assertNull(registry.get("shared"))
        first.cancel(); second.cancel()
    }

    @Test
    fun `delay milliseconds honors edited value and zero without fallback`() = runTest {
        val node = WorkflowNode("wait", WorkflowNodeType.DELAY, "等待", config = mapOf("milliseconds" to "125"))
        val output = DelayNodeExecutor().execute(node, context) { _, _ -> }
        assertEquals(125L, output.durationMs)
        assertEquals("125", output.variables["DELAY_MS"])
        assertEquals(0L, DelayNodeExecutor().execute(node.copy(config = mapOf("milliseconds" to "0")), context) { _, _ -> }.durationMs)
    }
}
