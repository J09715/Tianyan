package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class PhoneOperationWorkflowTest {
    @Test
    fun `recording builds an editable ordered success chain and text variables`() {
        val workflow = PhoneOperationWorkflow.create("recorded", "购物", listOf(
            PhoneWorkflowOperation("virtual_screen_launch", mapOf("package" to "com.example.app"), 900),
            PhoneWorkflowOperation("virtual_screen_click", mapOf("x" to "250", "y" to "750"), 100),
            PhoneWorkflowOperation("virtual_screen_set_text", mapOf("text" to "  中文\n文本  "), 50),
            PhoneWorkflowOperation("virtual_screen_wait", mapOf("duration_ms" to "0")),
        ))
        assertTrue(WorkflowValidator.validate(workflow).isEmpty())
        assertEquals("  中文\n文本  ", workflow.defaultVariables["TEXT_1"])
        assertEquals("\${TEXT_1}", workflow.nodes.first { it.id == "step_3" }.config["text"])
        assertEquals("100", workflow.nodes.first { it.id == "step_2" }.config["wait_ms"])
        assertEquals(workflow.nodes.map { it.id }.zipWithNext(), workflow.edges.map { it.fromNodeId to it.toNodeId })
        assertTrue(workflow.edges.all { it.fromPort == "success" })
        assertTrue(workflow.nodes.all { it.failurePolicy == FailurePolicy.ABORT })
        assertFalse(workflow.nodes.any { it.type == WorkflowNodeType.AGENT_INFERENCE })
    }

    @Test
    fun `all templates validate and virtual actions have unique stable IDs`() {
        BuiltinWorkflows.all.forEach { assertTrue("${it.id}: ${WorkflowValidator.validate(it)}", WorkflowValidator.validate(it).isEmpty()) }
        assertEquals(HostWorkflowActions.all.size, HostWorkflowActions.all.map { it.id }.distinct().size)
    }

    @Test
    fun `editor rejects out of range coordinates but allows variables`() {
        val workflow = VirtualScreenWorkflowActions.template()
        val click = WorkflowNode("click", WorkflowNodeType.HOST_ACTION, "点击",
            config = mapOf("action" to "virtual_screen_click", "x" to "1001", "y" to "10"))
        assertTrue(WorkflowValidator.validate(workflow.copy(nodes = workflow.nodes + click)).any { it.field.endsWith(".x") })
        assertTrue(WorkflowValidator.validate(workflow.copy(nodes = workflow.nodes + click.copy(
            config = click.config + ("x" to "\${X}"),
        ))).isEmpty())
    }
}
