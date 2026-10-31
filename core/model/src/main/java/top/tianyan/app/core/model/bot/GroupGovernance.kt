package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

/** Reporting lines and review authority are deliberately independent. */
@Serializable
data class GroupReviewRule(
    val id: String,
    val name: String,
    val authorId: String,
    val reviewerId: String,
    val executorId: String,
    val maxRevisions: Int = 3,
)

@Serializable
data class GroupGovernance(val rules: List<GroupReviewRule> = emptyList()) {
    fun validate(team: GroupTeam) {
        require(rules.size <= 20 && rules.map { it.id }.distinct().size == rules.size) { "审议规则重复或超过20条" }
        require(team.memberIds.all { executionRules(team, it).size <= 1 }) { "执行团队不能被多条规则重复覆盖" }
        require(rules.all { executionRules(team, it.authorId).isEmpty() && executionRules(team, it.reviewerId).isEmpty() }) { "起草或审核成员不能处于受控执行团队内" }
        rules.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank()) { "请填写规则名称" }
            require(setOf(it.authorId, it.reviewerId, it.executorId).size == 3) { "起草、审核和执行须由不同成员负责" }
            require(listOf(it.authorId, it.reviewerId, it.executorId).all { id -> id in team.memberIds }) { "规则成员必须在本群" }
            require(it.maxRevisions in 1..10) { "修改上限须为1至10轮" }
        }
    }

    fun canRoute(caller: String, target: String): Boolean = rules.any {
        (caller == it.authorId && target == it.reviewerId) ||
            (caller in setOf(it.authorId, it.reviewerId) && target == it.executorId)
    }

    fun executionRules(team: GroupTeam, botId: String): List<GroupReviewRule> =
        rules.filter { team.canInspect(it.executorId, botId) }
}

@Serializable
enum class ProposalDecision { PENDING, APPROVED, REJECTED, ESCALATED }

@Serializable
data class GroupProposal(
    val id: String,
    val groupId: String,
    val rule: GroupReviewRule,
    val version: Int,
    val content: String,
    val decision: ProposalDecision = ProposalDecision.PENDING,
    val reason: String = "",
    val decidedBy: String? = null,
    val teamGroupId: String? = null,
    val teamVersion: Int? = null,
    val rootWorkId: String? = null,
)
