package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.GroupGovernance
import top.tianyan.app.core.model.bot.GroupTeam

internal data class TeamGraphNode(val id: String, val x: Float, val y: Float)
internal enum class TeamGraphEdgeKind { MANAGEMENT, REVIEW, APPROVED_EXECUTION, REJECTION, ESCALATION }
internal data class TeamGraphEdge(val from: String, val to: String, val kind: TeamGraphEdgeKind)
internal data class TeamGraphLayout(val nodes: List<TeamGraphNode>, val edges: List<TeamGraphEdge>, val width: Float, val height: Float)

/** A reporting tree determines positions; review loops never participate in tree layout. */
internal fun layoutTeamGraph(team: GroupTeam, governance: GroupGovernance): TeamGraphLayout {
    if (team.adminBotId !in team.memberIds) return TeamGraphLayout(emptyList(), emptyList(), 320f, 100f)
    val nodes = mutableListOf<TeamGraphNode>()
    val seen = mutableSetOf<String>()
    var nextLeaf = 0
    fun visit(id: String, depth: Int): Float {
        if (!seen.add(id)) return 0f
        val children = team.directReports(id).filter { it !in seen }.sorted()
        val x = if (children.isEmpty()) 24f + 192f * nextLeaf++ else children.map { visit(it, depth + 1) }.average().toFloat()
        nodes += TeamGraphNode(id, x, 42f + depth * 180f)
        return x
    }
    visit(team.adminBotId, 0)
    team.memberIds.filter { it !in seen }.sorted().forEach { visit(it, 1) }
    val management = nodes.mapNotNull { node -> team.supervisorOf(node.id)?.let { TeamGraphEdge(it, node.id, TeamGraphEdgeKind.MANAGEMENT) } }
    val review = governance.rules.flatMap {
        listOf(TeamGraphEdge(it.authorId, it.reviewerId, TeamGraphEdgeKind.REVIEW),
            TeamGraphEdge(it.reviewerId, it.executorId, TeamGraphEdgeKind.APPROVED_EXECUTION),
            TeamGraphEdge(it.reviewerId, it.authorId, TeamGraphEdgeKind.REJECTION),
            TeamGraphEdge(it.reviewerId, team.adminBotId, TeamGraphEdgeKind.ESCALATION))
    }.filter { it.from in team.memberIds && it.to in team.memberIds }
    return TeamGraphLayout(nodes, (management + review).distinct(), (nodes.maxOfOrNull { it.x } ?: 0f) + 192f,
        (nodes.maxOfOrNull { it.y } ?: 0f) + 140f)
}
