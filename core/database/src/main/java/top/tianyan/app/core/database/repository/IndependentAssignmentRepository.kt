package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.BotAssignmentDao
import top.tianyan.app.core.database.BotAssignmentRequestEntity
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.toEntity
import top.tianyan.app.core.database.toModel
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentRequest
import top.tianyan.app.core.model.bot.BotWorkBatch

interface IndependentAssignmentRepository {
    suspend fun createIndependent(
        task: BotAssignment,
        session: HarnessSessionEntity,
        request: BotAssignmentRequest,
        batch: BotWorkBatch? = null,
    )
    suspend fun enqueueRequest(request: BotAssignmentRequest)
    /** Atomic get-or-create; never creates a session/request for a duplicate work item. */
    suspend fun createIndependentOnce(task: BotAssignment, session: HarnessSessionEntity,
        request: BotAssignmentRequest, batch: BotWorkBatch? = null, operationId: String? = null, nativeTaskId: String? = null): String {
        createIndependent(task, session, request, batch)
        return task.id
    }
    suspend fun dispatchedBots(groupId: String, operationId: String, nativeTaskId: String? = null): Set<String> = emptySet()
    fun pendingRequests(): Flow<List<BotAssignmentRequest>>
    fun observeRequest(id: String): Flow<BotAssignmentRequest?>
    suspend fun getRequest(id: String): BotAssignmentRequest?
    suspend fun requestsFor(id: String): List<BotAssignmentRequest>
    suspend fun assignmentForSession(sessionId: String): BotAssignment?
    suspend fun assignmentsForBot(botId: String): List<BotAssignment>
    suspend fun claimRequest(id: String, now: Long): Boolean
    suspend fun admitRequest(id: String, nativeTaskId: String?): Boolean
    suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?)
    suspend fun reportBlock(sessionId: String, reason: String?, artifacts: List<String>): Boolean
    suspend fun recoverRequests()
    suspend fun cancelAssignment(id: String)
    suspend fun acknowledgeSynchronousResult(id: String, revision: Int, status: String, sourceSessionId: String) {}
}

internal class RoomIndependentAssignmentRepository(private val dao: BotAssignmentDao, private val json: Json) : IndependentAssignmentRepository {
    override suspend fun createIndependent(
        task: BotAssignment,
        session: HarnessSessionEntity,
        request: BotAssignmentRequest,
        batch: BotWorkBatch?,
    ) { dao.createIndependent(task.toEntity(json), session, request.toEntity(), batch?.toEntity()) }
    override suspend fun createIndependentOnce(task: BotAssignment, session: HarnessSessionEntity,
        request: BotAssignmentRequest, batch: BotWorkBatch?, operationId: String?, nativeTaskId: String?): String =
        dao.createIndependent(task.toEntity(json), session, request.toEntity(), batch?.toEntity(), operationId, nativeTaskId)
    override suspend fun dispatchedBots(groupId: String, operationId: String, nativeTaskId: String?) = dao.dispatchedBots(groupId, operationId, nativeTaskId).toSet()
    override suspend fun enqueueRequest(request: BotAssignmentRequest) = dao.enqueueRequest(request.toEntity())
    override fun pendingRequests() = dao.pendingAssignmentRequests().map { rows -> rows.map { it.model() } }
    override fun observeRequest(id: String) = dao.observeAssignmentRequest(id).map { it?.model() }
    override suspend fun getRequest(id: String) = dao.findAssignmentRequest(id)?.model()
    override suspend fun requestsFor(id: String) = dao.assignmentRequests(id).map { it.model() }
    override suspend fun assignmentForSession(sessionId: String) = dao.assignmentForSession(sessionId)?.toModel(json)
    override suspend fun assignmentsForBot(botId: String) = dao.assignmentsForBot(botId).map { it.toModel(json) }
    override suspend fun claimRequest(id: String, now: Long) = dao.claimRequest(id, now)
    override suspend fun admitRequest(id: String, nativeTaskId: String?) = dao.admitAssignmentRequest(id, nativeTaskId) == 1
    override suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?) =
        dao.finishRequest(id, success, uncertain, summary, error, System.currentTimeMillis())
    override suspend fun reportBlock(sessionId: String, reason: String?, artifacts: List<String>): Boolean {
        val task = dao.assignmentForSession(sessionId) ?: return false
        return dao.declareAssignmentBlock(task.activeRequestId ?: return false, reason, json.encodeToString(artifacts)) == 1
    }
    override suspend fun recoverRequests() = dao.recoverRequests(System.currentTimeMillis())
    override suspend fun cancelAssignment(id: String) = dao.cancelAssignment(id, System.currentTimeMillis())
    override suspend fun acknowledgeSynchronousResult(id: String, revision: Int, status: String, sourceSessionId: String) =
        dao.acknowledgeSynchronousResult(id, revision, status, sourceSessionId)
    private fun BotAssignmentRequest.toEntity() = BotAssignmentRequestEntity(id, assignmentId, instruction, createdAt)
    private fun BotAssignmentRequestEntity.model() = BotAssignmentRequest(id, assignmentId, instruction, createdAt, status,
        nativeTaskId, summary, error, blockedReason, runCatching { json.decodeFromString<List<String>>(artifactsJson) }.getOrDefault(emptyList()))
}
