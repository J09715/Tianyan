package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotWorkBatch
import top.tianyan.app.core.model.bot.BotWorkBatchStatus

class RoomBotAssignmentRepository(
    private val dao: BotAssignmentDao,
    private val json: Json,
) : BotAssignmentRepository, top.tianyan.app.core.database.repository.IndependentAssignmentRepository by
    top.tianyan.app.core.database.repository.RoomIndependentAssignmentRepository(dao, json) {
    override fun observeForGroup(groupId: String): Flow<List<BotAssignment>> =
        dao.observeForGroup(groupId).map { rows -> rows.map { it.toModel(json) } }

    override suspend fun listRunning(groupId: String): List<BotAssignment> =
        dao.listRunning(groupId).map { it.toModel(json) }

    override suspend fun listRecent(limit: Int): List<BotAssignment> =
        dao.listRecent(limit).map { it.toModel(json) }

    override suspend fun get(id: String): BotAssignment? = dao.findById(id)?.let { it.toModel(json) }

    override suspend fun save(assignment: BotAssignment) = dao.upsert(assignment.toEntity(json))

    override suspend fun finish(
        id: String,
        status: BotAssignmentStatus,
        summary: String?,
        error: String?,
        finishedAt: Long,
    ) = dao.finish(id, status.name, finishedAt, summary, error)

    override suspend fun failRunning(now: Long, error: String) = dao.failRunning(now, error)

    override suspend fun getBatch(id: String): BotWorkBatch? = dao.findBatch(id)?.toModel()

    override suspend fun listBatches(groupId: String, limit: Int): List<BotWorkBatch> =
        dao.listBatches(groupId, limit).map { it.toModel() }

    override suspend fun batchAssignments(batchId: String): List<BotAssignment> =
        dao.batchAssignments(batchId).map { it.toModel(json) }

    override suspend fun batchTaskCount(batchId: String): Int = dao.batchTaskCount(batchId)

    override suspend fun reconcileWaitingBatches(now: Long) = dao.reconcileWaitingBatches(now)
}

internal fun BotAssignmentEntity.toModel(json: Json) = BotAssignment(
    id = id,
    groupId = groupId,
    botId = botId,
    botName = botName,
    title = title,
    task = task,
    status = runCatching { BotAssignmentStatus.valueOf(status) }
        .getOrDefault(BotAssignmentStatus.FAILED),
    writePaths = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), writePathsJson)
    }.getOrDefault(emptyList()),
    createdAt = createdAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
    summary = summary,
    error = error,
    sessionId = sessionId,
    laneName = laneName,
    reportToBotId = reportToBotId,
    independent = independent, revision = revision, activeRequestId = activeRequestId,
    dependsOn = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), dependsOnJson)
    }.getOrDefault(emptyList()),
    batchId = batchId,
    parentAssignmentId = parentAssignmentId, budgetId = budgetId,
    proposalId = proposalId, proposalVersion = proposalVersion, teamBinding = teamBinding,
    rootWorkId = rootWorkId, stageId = stageId, workItemId = workItemId,
)

internal fun BotAssignment.toEntity(json: Json) = BotAssignmentEntity(
    id = id,
    groupId = groupId,
    botId = botId,
    botName = botName,
    title = title,
    task = task,
    status = status.name,
    writePathsJson = json.encodeToString(ListSerializer(String.serializer()), writePaths),
    createdAt = createdAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
    summary = summary,
    error = error,
    sessionId = sessionId,
    laneName = laneName,
    reportToBotId = reportToBotId,
    independent = independent, revision = revision, activeRequestId = activeRequestId,
    dependsOnJson = json.encodeToString(ListSerializer(String.serializer()), dependsOn),
    batchId = batchId,
    parentAssignmentId = parentAssignmentId, budgetId = budgetId,
    proposalId = proposalId, proposalVersion = proposalVersion, teamBinding = teamBinding,
    rootWorkId = rootWorkId, stageId = stageId, workItemId = workItemId,
)

internal fun BotWorkBatchEntity.toModel() = BotWorkBatch(
    id = id,
    groupId = groupId,
    requesterBotId = requesterBotId,
    requesterSessionId = requesterSessionId,
    originOperationId = originOperationId,
    revision = revision,
    reportRevision = reportRevision,
    status = runCatching { BotWorkBatchStatus.valueOf(status) }
        .getOrDefault(BotWorkBatchStatus.SETTLED),
    createdAt = createdAt,
    updatedAt = updatedAt,
    settledAt = settledAt,
)

internal fun BotWorkBatch.toEntity() = BotWorkBatchEntity(
    id = id,
    groupId = groupId,
    requesterBotId = requesterBotId,
    requesterSessionId = requesterSessionId,
    originOperationId = originOperationId,
    revision = revision,
    reportRevision = reportRevision,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    settledAt = settledAt,
)
