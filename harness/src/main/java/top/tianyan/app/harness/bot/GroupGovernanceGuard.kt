package top.tianyan.app.harness.bot

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.ProposalDecision
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

/** Runs even for approved/replayed calls. A team review is separate from tool approval. */
class GroupGovernanceGuard(private val groups: GroupChatRepository, private val bots: PersonaBotRepository,
    private val tasks: BotAssignmentRepository) {
    suspend fun blockReason(sessionId: String, call: ToolCall): String? = try {
        verify(sessionId, call)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { "无法核验团队审议状态，本次工具未执行；请先核对群聊与方案记录" }

    private suspend fun verify(sessionId: String, call: ToolCall): String? {
        val task = tasks.assignmentForSession(sessionId)
        val groupId = task?.groupId ?: bots.observeGroupSessionRefs().first()[sessionId]?.groupId ?: return null
        val group = groups.getGroup(groupId)
        val binding = group?.teamGroupId?.let { "$it@${group.teamVersion}" }
        if (task != null && task.teamBinding != binding) return "团队版本已变化，旧任务不能沿用新版本权限，请创建新任务"
        val policy = groups.getGovernance(groupId)
        if (policy.rules.isEmpty()) return null
        val team = groups.getTeam(groupId)
        if (runCatching { policy.validate(team) }.isFailure) return "审议成员或组织已变化，请在团队组中修正规则"
        val botId = task?.botId ?: bots.getBotBySessionId(sessionId)?.id
        if (botId !in team.memberIds) return "执行成员已移出群聊"
        if (call.tool in READ_ONLY) return null
        if (call.tool == HarnessTool.MCP && call.rawToolName == "use_capability") {
            val action = (call.args["action"] as? JsonPrimitive)?.content
            if (action in setOf("inspect", "list")) return null
            if (action == "call" && (call.args["server"] as? JsonPrimitive)?.content == "plugin.tianyan.bot-management" &&
                (call.args["tool"] as? JsonPrimitive)?.content in TEAM_TOOLS) return null
        }
        if (task != null && task.status.name == "RUNNING") {
            val proposal = task.proposalId?.let { groups.getProposal(it) }
            if (proposal != null && proposal.groupId == groupId && proposal.version == task.proposalVersion &&
                proposal.teamGroupId == group?.teamGroupId && proposal.teamVersion == group?.teamVersion && proposal.rule in policy.rules && proposal.decision == ProposalDecision.APPROVED &&
                policy.executionRules(team, task.botId).singleOrNull() == proposal.rule) return null
        }
        return "团队审议门禁：仅已批准准确方案版本的执行任务可调用此工具；请先完成起草、审核并以proposal_id派发执行。"
    }

    companion object {
        private val READ_ONLY = setOf(HarnessTool.READ, HarnessTool.HISTORY_SEARCH, HarnessTool.HISTORY_READ,
            HarnessTool.PLAN, HarnessTool.SCRATCHPAD, HarnessTool.ASK_USER, HarnessTool.LOAD_RULE,
            HarnessTool.LOAD_SKILL, HarnessTool.COMPRESS)
        private val TEAM_TOOLS = setOf("list-bots", "list-assignments", "inspect-assignment", "assign-task",
            "continue-assignment", "cancel-assignment", "report-assignment", "list-models", "select-model",
            "inspect-governance", "submit-proposal", "review-proposal")
    }
}
