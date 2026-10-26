package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

@Serializable
enum class GroupSlotStatus { WAITING, SPEAKING, POSTED, PASSED, WORKING, FAILED, LEFT }

@Serializable
data class GroupRoundSlot(
    val botId: String,
    val name: String,
    var status: GroupSlotStatus = GroupSlotStatus.WAITING,
    var trigger: String? = null,
    var calledBy: String? = null,
    val rootWorkId: String? = null,
)

@Serializable
enum class GroupRoundStatus { RUNNING, DONE, CANCELLED }

@Serializable
data class GroupRound(
    val id: String,
    val groupId: String,
    val trigger: String,
    val imageUrls: List<String> = emptyList(),
    val slots: List<GroupRoundSlot>,
    val status: GroupRoundStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class GroupLateReplyStatus { WATCHING, DONE, FAILED }

@Serializable
data class GroupLateReply(
    val id: String,
    val groupId: String,
    val workerId: String,
    val workerName: String,
    val sessionId: String,
    val placeholderId: String,
    val callerBotId: String? = null,
    val createdAt: Long,
    val status: GroupLateReplyStatus,
    val error: String? = null,
    val taskId: String? = null,
)
