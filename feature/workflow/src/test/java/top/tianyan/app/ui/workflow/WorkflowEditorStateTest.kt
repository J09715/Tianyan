package top.tianyan.app.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport
import top.tianyan.app.core.model.workflow.WorkflowDefinitionExport
import top.tianyan.app.core.model.workflow.WorkflowEdge
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType

class WorkflowEditorStateTest {
    private val base = WorkflowDefinition(
        id = "custom",
        name = "Custom",
        nodes = listOf(
            WorkflowNode("a", WorkflowNodeType.TRIGGER, "A"),
            WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "B"),
            WorkflowNode("c", WorkflowNodeType.TERMINAL_OUTPUT, "C"),
        ),
        edges = listOf(WorkflowEdge("ab", "a", toNodeId = "b")),
    )

    @Test
    fun `imported definitions start unsaved and preserve references through editing`() {
        val imported = WorkflowDefinitionImport.fresh(base)
        val history = WorkflowEditHistory(imported, initiallySaved = false)
        assertTrue(history.isDirty)
        assertFalse(history.canUndo)
        history.commit(updatedWorkflowMetadata(imported, "Imported", "", "", 2))
        assertTrue(history.undo())
        assertEquals(base.nodes, history.current.nodes)
        assertEquals(base.edges, history.current.edges)
        history.markSaved()
        assertFalse(history.isDirty)
        assertTrue(history.current.id != base.id)
    }

    @Test
    fun `exporting applied draft preserves unsaved edits without changing edit history`() {
        val history = WorkflowEditHistory(base, initiallySaved = true)
        val draft = updatedWorkflowMetadata(base, "Draft", "unsaved", "custom", 2)
        history.commit(draft)
        val exported = WorkflowDefinitionImport.parse(WorkflowDefinitionExport.encode(history.current))
        assertEquals("Draft", exported.name)
        assertEquals(2, exported.maxParallelNodes)
        assertEquals(base.nodes, exported.nodes)
        assertTrue(history.isDirty)
        assertTrue(history.undo())
        assertEquals(base, history.current)
        assertEquals("Draft", exported.name)
    }

    @Test
    fun `history supports undo redo and saved baseline`() {
        val history = WorkflowEditHistory(base, initiallySaved = true)
        history.commit(base.copy(name = "Changed"))
        assertTrue(history.isDirty)
        assertTrue(history.undo())
        assertEquals("Custom", history.current.name)
        assertFalse(history.isDirty)
        assertTrue(history.redo())
        history.markSaved()
        assertFalse(history.isDirty)
    }

    @Test
    fun `parallelism metadata participates in undo redo without changing graph`() {
        val history = WorkflowEditHistory(base, initiallySaved = true)
        history.commit(updatedWorkflowMetadata(base, "Changed", "description", "category", 1))
        assertEquals(1, history.current.maxParallelNodes)
        assertEquals(base.nodes, history.current.nodes)
        assertEquals(base.edges, history.current.edges)
        assertTrue(history.undo())
        assertEquals(4, history.current.maxParallelNodes)
        assertTrue(history.redo())
        assertEquals(1, history.current.maxParallelNodes)
        assertThrows(IllegalArgumentException::class.java) { updatedWorkflowMetadata(base, "Changed", "", "", 0) }
    }

    @Test
    fun `removing a node also removes attached edges`() {
        val result = WorkflowGraphEditor.removeNode(base, "b")
        assertEquals(listOf("a", "c"), result.nodes.map { it.id })
        assertTrue(result.edges.isEmpty())
    }

    @Test
    fun `connection that creates cycle is rejected`() {
        val chained = WorkflowGraphEditor.connect(base, WorkflowEdge("bc", "b", toNodeId = "c"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowGraphEditor.connect(chained, WorkflowEdge("ca", "c", toNodeId = "a"))
        }
    }

    @Test
    fun `edge port and condition can be edited and invalid regex is rejected`() {
        val updated = WorkflowGraphEditor.updateEdge(
            base,
            base.edges.single().copy(fromPort = "failure", conditionExpression = "output contains timeout"),
        )
        assertEquals("failure", updated.edges.single().fromPort)
        assertEquals("output contains timeout", updated.edges.single().conditionExpression)

        assertThrows(IllegalArgumentException::class.java) {
            WorkflowGraphEditor.updateEdge(updated, updated.edges.single().copy(conditionExpression = "["))
        }
    }
}
