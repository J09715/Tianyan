package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.GroupGovernanceDao
import top.tianyan.app.core.model.bot.*

interface GroupGovernanceRepository {
    suspend fun getGovernance(groupId: String): GroupGovernance = GroupGovernance()
    fun observeGovernance(groupId: String): Flow<GroupGovernance> = flowOf(GroupGovernance())
    suspend fun saveGovernance(groupId: String, policy: GroupGovernance) { error("审议配置不可用") }
    fun observeProposals(groupId: String): Flow<List<GroupProposal>> = flowOf(emptyList())
    suspend fun getProposal(id: String): GroupProposal? = null
    suspend fun submitProposal(groupId: String, callerId: String, ruleId: String, id: String,
        expectedVersion: Int, content: String, rootWorkId: String? = null): GroupProposal = error("方案提交不可用")
    suspend fun decideProposal(groupId: String, callerId: String, id: String, version: Int,
        decision: ProposalDecision, reason: String): GroupProposal = error("方案审核不可用")
}

class RoomGroupGovernanceRepository(private val dao: GroupGovernanceDao) : GroupGovernanceRepository {
    override suspend fun getGovernance(groupId: String) = dao.governance(groupId)
    override fun observeGovernance(groupId: String) = kotlinx.coroutines.flow.combine(
        dao.observeGovernanceRow(groupId), dao.observeLinkedDefinition(groupId)) { _, _ -> dao.governance(groupId) }
    override suspend fun saveGovernance(groupId: String, policy: GroupGovernance) = dao.saveGovernance(groupId, policy)
    override fun observeProposals(groupId: String) = dao.observeProposalRows(groupId).map { rows ->
        rows.map { Json.decodeFromString<GroupProposal>(it.payload) }
    }
    override suspend fun getProposal(id: String) = dao.proposal(id)
    override suspend fun submitProposal(groupId: String, callerId: String, ruleId: String, id: String,
        expectedVersion: Int, content: String, rootWorkId: String?) = dao.submitProposal(groupId, callerId, ruleId, id, expectedVersion, content, rootWorkId)
    override suspend fun decideProposal(groupId: String, callerId: String, id: String, version: Int,
        decision: ProposalDecision, reason: String) = dao.decideProposal(groupId, callerId, id, version, decision, reason)
}
