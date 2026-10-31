package top.tianyan.app.core.database.repository

import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupLateReplyStatus
import top.tianyan.app.core.model.bot.GroupRound

interface GroupRoundRepository {
    suspend fun insertRound(round: GroupRound)
    suspend fun updateRound(round: GroupRound)
    suspend fun updateRoundProgress(round: GroupRound)
    suspend fun updateRoundAttachments(round: GroupRound)
    suspend fun finishRound(round: GroupRound)
    suspend fun findRound(id: String): GroupRound?
    suspend fun runningRounds(): List<GroupRound>
    suspend fun cancelRounds(groupId: String, now: Long = System.currentTimeMillis())
    suspend fun insertLateReply(reply: GroupLateReply)
    suspend fun watchingLateReplies(): List<GroupLateReply>
    suspend fun finishLateReply(id: String, status: GroupLateReplyStatus, error: String? = null)
    suspend fun findLateReply(id: String): GroupLateReply?
    suspend fun pruneFinished(roundBefore: Long, lateBefore: Long)
}
