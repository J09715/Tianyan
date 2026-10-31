package top.tianyan.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.*

@Entity(tableName = "group_governance", foreignKeys = [ForeignKey(entity = GroupChatEntity::class,
    parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class GroupGovernanceEntity(@PrimaryKey val groupId: String, val payload: String)

@Entity(tableName = "group_proposals", primaryKeys = ["id", "version"],
    foreignKeys = [ForeignKey(entity = GroupChatEntity::class, parentColumns = ["id"],
        childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)], indices = [Index("groupId")])
data class GroupProposalEntity(val id: String, val version: Int, val groupId: String, val payload: String)

@Dao
interface GroupGovernanceDao : GroupTeamDao {
    @Query("SELECT * FROM group_governance WHERE groupId = :groupId")
    suspend fun governanceRow(groupId: String): GroupGovernanceEntity?

    @Query("SELECT * FROM group_governance WHERE groupId = :groupId")
    fun observeGovernanceRow(groupId: String): Flow<GroupGovernanceEntity?>

    @Upsert suspend fun putGovernance(row: GroupGovernanceEntity)

    @Query("SELECT * FROM group_proposals WHERE id = :id ORDER BY version DESC LIMIT 1")
    suspend fun proposalRow(id: String): GroupProposalEntity?

    @Query("SELECT * FROM group_proposals WHERE groupId = :groupId ORDER BY id, version DESC")
    fun observeProposalRows(groupId: String): Flow<List<GroupProposalEntity>>

    @Upsert suspend fun putProposal(row: GroupProposalEntity)

    suspend fun governance(groupId: String): GroupGovernance {
        if (linkedTeamId(groupId) != null) return Json.decodeFromString<BotTeamDefinition>(requireNotNull(linkedDefinition(groupId))).governance
        return governanceRow(groupId)?.let {
        Json.decodeFromString<GroupGovernance>(it.payload)
    } ?: GroupGovernance()
    }

    suspend fun proposal(id: String): GroupProposal? = proposalRow(id)?.let { Json.decodeFromString<GroupProposal>(it.payload) }

    suspend fun governanceTeam(groupId: String): GroupTeam = effectiveTeam(groupId)

    @Transaction suspend fun saveGovernance(groupId: String, policy: GroupGovernance) {
        require(linkedTeamId(groupId) == null) { "审议规则请在团队组中修改" }
        policy.validate(governanceTeam(groupId))
        putGovernance(GroupGovernanceEntity(groupId, Json.encodeToString(policy)))
    }

    @Transaction suspend fun submitProposal(groupId: String, callerId: String, ruleId: String,
        proposalId: String, expectedVersion: Int, content: String, rootWorkId: String? = null): GroupProposal {
        val team = governanceTeam(groupId)
        val policy = governance(groupId).also { it.validate(team) }
        val rule = requireNotNull(policy.rules.find { it.id == ruleId }) { "审议规则不存在" }
        require(callerId == rule.authorId) { "只有指定起草成员可提交方案" }
        require(content.isNotBlank() && content.length <= 32_000) { "方案须为1至32000字" }
        val old = proposal(proposalId)
        require(old?.groupId == null || old.groupId == groupId) { "方案不属于本群" }
        require((old?.version ?: 0) == expectedVersion) { "方案版本已变化，请重新读取" }
        require(old == null || old.rule == rule && old.teamGroupId == linkedTeamId(groupId) && old.teamVersion == linkedVersion(groupId)) { "规则已变化，请新建方案" }
        require(old == null || old.decision in setOf(ProposalDecision.REJECTED, ProposalDecision.APPROVED)) { "只有已驳回或已批准的方案可以修改，新版本须重新审核" }
        val next = GroupProposal(proposalId, groupId, rule, expectedVersion + 1, content,
            if (expectedVersion >= rule.maxRevisions) ProposalDecision.ESCALATED else ProposalDecision.PENDING, teamGroupId = linkedTeamId(groupId), teamVersion = linkedVersion(groupId), rootWorkId = old?.rootWorkId ?: rootWorkId)
        require(next.version <= rule.maxRevisions + 1) { "已达修改上限，请提交主控裁决" }
        putProposal(GroupProposalEntity(next.id, next.version, groupId, Json.encodeToString(next)))
        return next
    }

    @Transaction suspend fun decideProposal(groupId: String, callerId: String, id: String, version: Int,
        decision: ProposalDecision, reason: String): GroupProposal {
        val current = requireNotNull(proposal(id)) { "方案不存在" }
        val team = governanceTeam(groupId)
        val policy = governance(groupId).also { it.validate(team) }
        require(current.groupId == groupId && current.version == version) { "方案不属于本群或版本已变化" }
        require(current.rule in policy.rules && current.teamGroupId == linkedTeamId(groupId) && current.teamVersion == linkedVersion(groupId)) { "规则已变化，请重新提交方案" }
        require(callerId in team.memberIds && (callerId == current.rule.reviewerId ||
            (callerId == team.adminBotId && current.decision == ProposalDecision.ESCALATED))) { "无权审核或裁决此方案" }
        require(current.decision in setOf(ProposalDecision.PENDING, ProposalDecision.ESCALATED)) { "本版已裁决，不得覆盖" }
        require(current.decision != ProposalDecision.ESCALATED || callerId == team.adminBotId) { "修改次数已达上限，请由群主控裁决" }
        require(decision in setOf(ProposalDecision.APPROVED, ProposalDecision.REJECTED, ProposalDecision.ESCALATED))
        require(reason.isNotBlank() && reason.length <= 4000) { "须提供审核依据（最多4000字）" }
        val next = current.copy(decision = decision, reason = reason, decidedBy = callerId)
        putProposal(GroupProposalEntity(id, version, groupId, Json.encodeToString(next)))
        return next
    }
}

/** Admission and each execution claim recheck the precise approved version. */
internal suspend fun BotAssignmentDao.governanceAllows(task: BotAssignmentEntity): Boolean {
    val group = task.groupId ?: return true
    if (task.teamBinding != linkedBinding(group)) return false
    val policy = governance(group)
    val team = governanceTeam(group)
    if (runCatching { policy.validate(team) }.isFailure) return false
    val required = policy.executionRules(team, task.botId)
    if (task.proposalId == null) return required.isEmpty()
    val proposal = proposal(task.proposalId) ?: return false
    if (proposal.groupId != group || proposal.version != task.proposalVersion || proposal.rule !in policy.rules || proposal.teamGroupId != linkedTeamId(group) || proposal.teamVersion != linkedVersion(group)) return false
    if (required.isNotEmpty()) return required.all { it == proposal.rule } && proposal.decision == ProposalDecision.APPROVED
    return task.botId == proposal.rule.reviewerId && proposal.decision == ProposalDecision.PENDING
}
