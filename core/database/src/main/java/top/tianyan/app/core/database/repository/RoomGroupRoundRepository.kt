package top.tianyan.app.core.database.repository

import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.GroupChatDao
import top.tianyan.app.core.model.bot.GroupRound
import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupLateReplyStatus

internal class RoomGroupRoundRepository(
    private val dao: GroupChatDao,
    private val json: Json,
) : GroupRoundRepository {
    override suspend fun insertRound(round: GroupRound) = dao.upsertRound(round.toEntity(json))

    override suspend fun updateRound(round: GroupRound) = dao.upsertRound(round.toEntity(json))

    override suspend fun updateRoundProgress(round: GroupRound) {
        check(dao.updateRoundProgress(round.id, round.slots.toSlotsJson(json), round.status.name, round.updatedAt) == 1) {
            "群聊轮次已停止或清除，未继续执行"
        }
    }

    override suspend fun updateRoundAttachments(round: GroupRound) =
        dao.updateRoundAttachments(
            round.id, round.trigger, round.imageUrls.toImageUrlsJson(json), round.updatedAt,
        )

    override suspend fun finishRound(round: GroupRound) {
        check(dao.finishRound(round.id, round.slots.toSlotsJson(json), round.status.name, round.updatedAt) == 1) {
            "群聊轮次已停止或清除，未覆盖终态"
        }
    }

    override suspend fun findRound(id: String): GroupRound? = dao.findRound(id)?.toModel(json)

    override suspend fun runningRounds(): List<GroupRound> = dao.runningRounds().map { it.toModel(json) }

    override suspend fun cancelRounds(groupId: String, now: Long) = dao.cancelRounds(groupId, now)

    override suspend fun insertLateReply(reply: GroupLateReply) = dao.upsertLateReply(reply.toEntity())

    override suspend fun watchingLateReplies(): List<GroupLateReply> =
        dao.watchingLateReplies().map { it.toModel() }

    override suspend fun finishLateReply(id: String, status: GroupLateReplyStatus, error: String?) =
        dao.updateLateReplyStatus(id, status.name, error)

    override suspend fun findLateReply(id: String): GroupLateReply? = dao.findLateReply(id)?.toModel()

    override suspend fun pruneFinished(roundBefore: Long, lateBefore: Long) {
        dao.pruneFinishedRounds(roundBefore)
        dao.pruneFinishedLateReplies(lateBefore)
    }

}
