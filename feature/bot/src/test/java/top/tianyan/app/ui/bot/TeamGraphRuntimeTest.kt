package top.tianyan.app.ui.bot

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.*

class TeamGraphRuntimeTest {
    private val group=GroupChat("g","group","chief",createdAt=1,teamGroupId="t",teamVersion=2)
    private val team=GroupTeam("chief",setOf("chief","author","reviewer","executor"))
    private val rule=GroupReviewRule("r","review","author","reviewer","executor")
    private val policy=GroupGovernance(listOf(rule))
    private fun proposal(id: String="p",version: Int=1,decision: ProposalDecision=ProposalDecision.PENDING)=GroupProposal(id,"g",rule,version,"plan",decision,teamGroupId="t",teamVersion=2)
    private fun task(id: String,status: BotAssignmentStatus)=BotAssignment(id,"g","executor","executor",id,id,status,createdAt=1,startedAt=1)

    @Test fun latestProposalSupersedesOldApprovalAndLightsOnlyCurrentReviewEdge() {
        val state=buildTeamRuntime(group,team,policy,emptyList(),listOf(proposal(decision=ProposalDecision.APPROVED),proposal(version=2)))
        assertEquals(1,state.edges.size)
        assertEquals("方案 v2 · 待审",state.edges.getValue(TeamGraphEdge("author","reviewer",TeamGraphEdgeKind.REVIEW)).label)
        assertEquals("等待审议 1",state.nodes.getValue("reviewer").label)
        assertEquals(listOf(2),state.proposals.map { it.version })
    }

    @Test fun otherRoomsOldTeamVersionsAndChangedRulesDoNotLightCurrentGraph() {
        val records=listOf(proposal().copy(groupId="other"),proposal("old").copy(teamVersion=1),proposal("rule").copy(rule=rule.copy(maxRevisions=5)))
        val state=buildTeamRuntime(group,team,policy,emptyList(),records)
        assertTrue(state.edges.isEmpty());assertTrue(state.proposals.isEmpty())
        assertTrue(state.nodes.values.all { it.label=="空闲" })
    }

    @Test fun blockedWorkHasPriorityAndFinishedHistoryDoesNotMarkMemberBusy() {
        val state=buildTeamRuntime(group,team,policy,listOf(task("a",BotAssignmentStatus.RUNNING),task("b",BotAssignmentStatus.BLOCKED)),emptyList())
        assertTrue(state.nodes.getValue("executor").blocked)
        assertEquals("受阻／待核对 1",state.nodes.getValue("executor").label)
        val done=buildTeamRuntime(group,team,policy,listOf(task("done",BotAssignmentStatus.COMPLETED)),emptyList())
        assertEquals("空闲",done.nodes.getValue("executor").label)
        assertEquals(1,done.tasks.size)
    }

    @Test fun escalationTargetsChiefAndSameEdgeRetainsAllPendingProposals() {
        val state=buildTeamRuntime(group,team,policy,emptyList(),listOf(proposal("p"),proposal("q"),proposal("e",decision=ProposalDecision.ESCALATED)))
        assertEquals("等待审议 1",state.nodes.getValue("chief").label)
        assertEquals("等待审议 2",state.nodes.getValue("reviewer").label)
        assertEquals(setOf("p","q","e"),state.edges.values.flatMap { it.proposalIds }.toSet())
    }
}
