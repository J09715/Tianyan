package top.tianyan.app.ui.bot

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.*

class TeamGraphLayoutTest {
    private val team = GroupTeam("root", setOf("root", "author", "reviewer", "executor", "worker"), mapOf("worker" to "executor"))
    private val policy = GroupGovernance(listOf(GroupReviewRule("r", "review", "author", "reviewer", "executor")))

    @Test fun `review and rejection loops appear as typed edges without duplicating members`() {
        val graph = layoutTeamGraph(team, policy)
        assertEquals(team.memberIds, graph.nodes.map { it.id }.toSet())
        assertEquals(team.memberIds.size, graph.nodes.size)
        assertEquals(4, graph.edges.count { it.kind == TeamGraphEdgeKind.MANAGEMENT })
        assertTrue(graph.edges.contains(TeamGraphEdge("author", "reviewer", TeamGraphEdgeKind.REVIEW)))
        assertTrue(graph.edges.contains(TeamGraphEdge("reviewer", "executor", TeamGraphEdgeKind.APPROVED_EXECUTION)))
        assertTrue(graph.edges.contains(TeamGraphEdge("reviewer", "author", TeamGraphEdgeKind.REJECTION)))
    }

    @Test fun `all nodes fit inside bounds and management descendants remain below their manager`() {
        val graph = layoutTeamGraph(team, policy)
        val nodes = graph.nodes.associateBy { it.id }
        graph.nodes.forEach { assertTrue(it.x >= 0 && it.x + 160 <= graph.width); assertTrue(it.y >= 0 && it.y + 76 <= graph.height) }
        graph.edges.filter { it.kind == TeamGraphEdgeKind.MANAGEMENT }.forEach {
            assertTrue(nodes.getValue(it.from).y < nodes.getValue(it.to).y)
        }
        assertEquals(graph.nodes.size, graph.nodes.map { it.x to it.y }.distinct().size)
    }

    @Test fun `layout is deterministic and incomplete draft does not crash graph rendering`() {
        assertEquals(layoutTeamGraph(team, policy), layoutTeamGraph(team.copy(memberIds = team.memberIds.reversed().toSet()), policy))
        assertTrue(layoutTeamGraph(GroupTeam("", emptySet()), policy).nodes.isEmpty())
    }
}
