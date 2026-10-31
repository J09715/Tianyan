package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.*

internal data class TeamNodeActivity(val label: String, val blocked: Boolean = false)
internal data class TeamEdgeActivity(val label: String, val proposalIds: List<String>)
internal data class TeamGraphRuntime(val group: GroupChat, val tasks: List<BotAssignment>, val proposals: List<GroupProposal>,
    val nodes: Map<String, TeamNodeActivity>, val edges: Map<TeamGraphEdge, TeamEdgeActivity>)

/** Always use each proposal's newest version; old approvals never light up a new team version. */
internal fun buildTeamRuntime(group: GroupChat, team: GroupTeam, policy: GroupGovernance,
    tasks: List<BotAssignment>, proposals: List<GroupProposal>, messages: List<GroupMessage> = emptyList()): TeamGraphRuntime {
    val current = proposals.filter { it.groupId == group.id }.groupBy { it.id }.values.map { it.maxBy { row -> row.version } }
        .filter { it.teamGroupId == group.teamGroupId && it.teamVersion == group.teamVersion && it.rule in policy.rules }
    val roomTasks = tasks.filter { it.groupId == group.id }
    val nodes = team.memberIds.associateWith { id ->
        val work = roomTasks.filter { it.botId == id }
        val blocked = work.count { it.status in setOf(BotAssignmentStatus.BLOCKED, BotAssignmentStatus.INTERRUPTED) }
        val running = work.count { it.status == BotAssignmentStatus.RUNNING }
        val pending = current.count { (it.decision == ProposalDecision.PENDING && it.rule.reviewerId == id) || (it.decision == ProposalDecision.ESCALATED && team.adminBotId == id) }
        TeamNodeActivity(when {
            blocked > 0 -> "受阻／待核对 $blocked"
            running > 0 -> "执行中 $running"
            messages.any { it.groupId == group.id && it.senderId == id && it.isStreaming } -> "正在回应"
            pending > 0 -> "等待审议 $pending"
            work.any { it.status == BotAssignmentStatus.WAITING_CHILDREN } -> "等待下级交付"
            work.any { it.status == BotAssignmentStatus.QUEUED } -> "任务排队中"
            else -> "空闲"
        }, blocked > 0)
    }
    val edges = linkedMapOf<TeamGraphEdge, TeamEdgeActivity>()
    current.groupBy { it.rule }.forEach { (rule, records) ->
        listOf(ProposalDecision.PENDING, ProposalDecision.REJECTED, ProposalDecision.APPROVED, ProposalDecision.ESCALATED).forEach decisionLoop@ { decision ->
            val rows = records.filter { it.decision == decision }
            if (rows.isEmpty()) return@decisionLoop
            val edge = when (decision) {
                ProposalDecision.PENDING -> TeamGraphEdge(rule.authorId, rule.reviewerId, TeamGraphEdgeKind.REVIEW)
                ProposalDecision.ESCALATED -> TeamGraphEdge(rule.reviewerId, team.adminBotId, TeamGraphEdgeKind.ESCALATION)
                ProposalDecision.REJECTED -> TeamGraphEdge(rule.reviewerId, rule.authorId, TeamGraphEdgeKind.REJECTION)
                ProposalDecision.APPROVED -> TeamGraphEdge(rule.reviewerId, rule.executorId, TeamGraphEdgeKind.APPROVED_EXECUTION)
            }
            val state = when (decision) { ProposalDecision.PENDING -> "待审"; ProposalDecision.ESCALATED -> "待主控裁决"; ProposalDecision.REJECTED -> "已封驳"; ProposalDecision.APPROVED -> "已批准" }
            val label = if (rows.size == 1) "方案 v${rows.single().version} · $state" else "${rows.size} 份方案 · $state"
            val previous = edges[edge]
            edges[edge] = TeamEdgeActivity(if (previous == null) label else "${previous.label}／$label", previous?.proposalIds.orEmpty() + rows.map { it.id })
        }
    }
    roomTasks.filter { it.status.isActive && team.canAssign(it.reportToBotId.orEmpty(), it.botId) }
        .groupBy { TeamGraphEdge(it.reportToBotId!!, it.botId, TeamGraphEdgeKind.MANAGEMENT) }.forEach { (edge, work) ->
            edges[edge] = TeamEdgeActivity("进行中 ${work.size}", work.mapNotNull { it.proposalId }.distinct())
        }
    return TeamGraphRuntime(group, roomTasks, current, nodes, edges)
}
