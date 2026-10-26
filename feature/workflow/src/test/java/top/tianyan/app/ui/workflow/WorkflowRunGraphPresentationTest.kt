package top.tianyan.app.ui.workflow

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowRunGraphPresentationTest {
    private fun node(id: String) = WorkflowNode(id, WorkflowNodeType.BASH_COMMAND, id,
        config = mapOf("command" to "echo \${previous.output}"), canvasX = 100f, canvasY = 100f)
    private fun edge(from: String, to: String) = WorkflowEdge("$from->$to", from, "success", to)
    private val graph = WorkflowDefinition("wf", "expanded", nodes = listOf(node("anchor"), node("anchor.left"),
        node("anchor.right"), node("anchor.join")), edges = listOf(edge("anchor", "anchor.left"),
        edge("anchor", "anchor.right"), edge("anchor.left", "anchor.join"), edge("anchor.right", "anchor.join")))

    @Test fun regularAndEditableDefinitionsKeepTheirCoordinates() {
        assertSame(graph, workflowRunGraphPresentation(graph, expanded = false))
    }

    @Test fun expandedForkJoinHasReadablePositionsWithoutChangingApprovedData() {
        val displayed = workflowRunGraphPresentation(graph, expanded = true)
        assertEquals(graph, displayed.copy(nodes = displayed.nodes.mapIndexed { index, node ->
            node.copy(canvasX = graph.nodes[index].canvasX, canvasY = graph.nodes[index].canvasY)
        }))
        val nodes = displayed.nodes.associateBy { it.id }
        displayed.edges.forEach { edge -> assertTrue(nodes.getValue(edge.toNodeId).canvasX > nodes.getValue(edge.fromNodeId).canvasX) }
        displayed.nodes.forEachIndexed { index, node -> displayed.nodes.drop(index + 1).forEach { other ->
            assertTrue(kotlin.math.abs(node.canvasX - other.canvasX) >= 228f || kotlin.math.abs(node.canvasY - other.canvasY) >= 118f)
        } }
        assertTrue(graph.nodes.all { it.canvasX == 100f && it.canvasY == 100f })
    }

    @Test fun anotherExpansionRelayoutsExistingCanvasPositionsAndIsDeterministic() {
        val first = workflowRunGraphPresentation(graph, expanded = true)
        val next = graph.copy(nodes = graph.nodes + node("anchor.next"), edges = graph.edges + edge("anchor", "anchor.next"))
        val displayed = workflowRunGraphPresentation(next, expanded = true)
        assertEquals(displayed, workflowRunGraphPresentation(next, expanded = true))
        val firstPositions = resolveNodePositions(first.nodes, emptyMap(), 2f)
        val nextPositions = resolveNodePositions(displayed.nodes, emptyMap(), 2f)
        val synchronized = synchronizeCanvasPositions(nextPositions, firstPositions, firstPositions)
        assertEquals(nextPositions, synchronized)
        assertEquals(next.nodes.map { it.id }.toSet(), synchronized.keys)
        assertEquals(4, graph.nodes.size)
        assertEquals(5, next.nodes.size)
    }

    @Test fun invalidLegacyGraphDoesNotCrashHistoryProjection() {
        val invalid = graph.copy(edges = graph.edges + edge("anchor.join", "anchor"))
        assertSame(invalid, workflowRunGraphPresentation(invalid, expanded = true))
    }

    @Test fun localDragSurvivesUnrelatedUpdatesWhileChangedSourceCoordinatesApplyImmediately() {
        val before = mapOf("a" to Offset(100f, 100f), "b" to Offset(400f, 100f))
        val dragging = before + ("a" to Offset(160f, 180f))
        assertEquals(dragging, synchronizeCanvasPositions(before, dragging, before))
        val moved = before + ("b" to Offset(700f, 220f))
        val synced = synchronizeCanvasPositions(moved, dragging, before)
        assertEquals(Offset(160f, 180f), synced.getValue("a"))
        assertEquals(Offset(700f, 220f), synced.getValue("b"))
    }

    @Test fun additionsRemovalsAndMissingLocalEntriesSynchronizeWithoutWaitingForEffects() {
        val before = mapOf("a" to Offset(100f, 100f), "removed" to Offset(400f, 100f))
        val next = mapOf("a" to Offset(100f, 100f), "added" to Offset(700f, 220f))
        assertEquals(next, synchronizeCanvasPositions(next, before, before))
        assertEquals(next, synchronizeCanvasPositions(next, emptyMap(), before))
        assertTrue(synchronizeCanvasPositions(emptyMap(), before, before).isEmpty())
    }
}
