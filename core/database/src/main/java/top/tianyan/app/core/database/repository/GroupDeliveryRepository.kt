package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.GroupChatDao
import top.tianyan.app.core.model.bot.GroupDelivery
import top.tianyan.app.core.model.bot.GroupMessage

interface GroupDeliveryRepository {
    suspend fun stageMessage(message: GroupMessage, botIds: List<String>)
    fun pendingDeliveries(): Flow<List<GroupDelivery>>
    suspend fun deliverySource(id: String): GroupMessage?
    suspend fun listDeliveries(groupId: String, limit: Int = 100): List<GroupDelivery>
    suspend fun claimDelivery(id: String): Boolean
    suspend fun prepareDelivery(id: String, sessionId: String): Boolean
    suspend fun admitDelivery(id: String, sessionId: String, taskId: String?): Boolean
    suspend fun finishDelivery(id: String, status: String, error: String? = null)
    suspend fun recoverDeliveries()
    suspend fun recoverOrphanStreams(notice: String = "⚠️ 群聊回复因进程退出中断，结果待核对")
    suspend fun cancelDeliveries(groupId: String)
}

internal class RoomGroupDeliveryRepository(private val dao: GroupChatDao, private val json: Json) : GroupDeliveryRepository {
    override suspend fun stageMessage(message: GroupMessage, botIds: List<String>) = dao.stageGroupDelivery(message.toEntity(json), botIds)
    override fun pendingDeliveries() = dao.pendingGroupDeliveries().map { rows -> rows.map { it.toModel() } }
    override suspend fun deliverySource(id: String) = dao.findMessage(id)?.toModel(json)
    override suspend fun listDeliveries(groupId: String, limit: Int) = dao.listGroupDeliveries(groupId, limit.coerceIn(1, 100)).map { it.toModel() }
    override suspend fun claimDelivery(id: String) = dao.claimGroupDelivery(id) == 1
    override suspend fun prepareDelivery(id: String, sessionId: String) = dao.prepareGroupDelivery(id, sessionId) == 1
    override suspend fun admitDelivery(id: String, sessionId: String, taskId: String?) = dao.admitGroupDelivery(id, sessionId, taskId) == 1
    override suspend fun finishDelivery(id: String, status: String, error: String?) = dao.finishGroupDelivery(id, status, error)
    override suspend fun recoverDeliveries() = dao.recoverGroupDeliveries()
    override suspend fun recoverOrphanStreams(notice: String) = dao.settleOrphanStreams(notice)
    override suspend fun cancelDeliveries(groupId: String) = dao.cancelGroupDeliveries(groupId)
}
