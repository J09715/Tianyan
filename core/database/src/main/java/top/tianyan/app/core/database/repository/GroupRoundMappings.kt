package top.tianyan.app.core.database.repository

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import top.tianyan.app.core.database.GroupLateReplyEntity
import top.tianyan.app.core.database.GroupRoundEntity
import top.tianyan.app.core.model.bot.GroupLateReply
import top.tianyan.app.core.model.bot.GroupLateReplyStatus
import top.tianyan.app.core.model.bot.GroupRound
import top.tianyan.app.core.model.bot.GroupRoundSlot
import top.tianyan.app.core.model.bot.GroupRoundStatus

internal fun List<GroupRoundSlot>.toSlotsJson(json: Json): String =
    json.encodeToString(ListSerializer(serializer<GroupRoundSlot>()), this)

internal fun List<String>.toImageUrlsJson(json: Json): String =
    json.encodeToString(ListSerializer(String.serializer()), this)

internal fun GroupRoundEntity.toModel(json: Json) = GroupRound(
    id = id,
    groupId = groupId,
    trigger = trigger,
    imageUrls = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), imageUrlsJson)
    }.getOrDefault(emptyList()),
    slots = runCatching {
        json.decodeFromString(ListSerializer(serializer<GroupRoundSlot>()), slotsJson)
    }.getOrDefault(emptyList()),
    status = runCatching { GroupRoundStatus.valueOf(status) }.getOrDefault(GroupRoundStatus.RUNNING),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun GroupRound.toEntity(json: Json) = GroupRoundEntity(
    id = id,
    groupId = groupId,
    trigger = trigger,
    imageUrlsJson = imageUrls.toImageUrlsJson(json),
    slotsJson = slots.toSlotsJson(json),
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun GroupLateReplyEntity.toModel() = GroupLateReply(
    id = id,
    groupId = groupId,
    workerId = workerId,
    workerName = workerName,
    sessionId = sessionId,
    placeholderId = placeholderId,
    callerBotId = callerBotId,
    createdAt = createdAt,
    status = runCatching { GroupLateReplyStatus.valueOf(status) }
        .getOrDefault(GroupLateReplyStatus.FAILED),
    error = error,
    taskId = taskId,
)

internal fun GroupLateReply.toEntity() = GroupLateReplyEntity(
    id = id,
    groupId = groupId,
    workerId = workerId,
    workerName = workerName,
    sessionId = sessionId,
    placeholderId = placeholderId,
    callerBotId = callerBotId,
    createdAt = createdAt,
    status = status.name,
    error = error,
    taskId = taskId,
)
