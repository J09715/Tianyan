package top.tianyan.app.harness.groupchat

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupRoundSlot
import top.tianyan.app.core.model.bot.GroupSlotStatus
import top.tianyan.app.harness.MentionExtractor

internal fun appendRoundMentions(
    slots: MutableList<GroupRoundSlot>,
    currentIndex: Int,
    text: String,
    members: List<Bot>,
    turns: Map<String, Int>,
    group: GroupChat,
    delegatedBotIds: Set<String> = emptySet(),
) {
    if (text.isBlank()) return
    val mentionedNames = MentionExtractor.parse(text, members.map { it.name })
    if (mentionedNames.isEmpty()) return
    val speakerId = slots.getOrNull(currentIndex)?.botId
    val mentioned = members.filter { member ->
        member.id != speakerId && mentionedNames.any { it.equals(member.name, ignoreCase = true) }
    }.let { targeted ->
        // 全体保留字只对管理员生效：普通成员 @所有人 不唤醒任何人，
        // 防止成员回复里的客套话互相唤起一大片；点名 @名字 的优先级高于全体。
        val speakerIsAdmin = speakerId != null && speakerId == group.adminBotId
        if (targeted.isEmpty() && speakerIsAdmin && MentionExtractor.containsAllKeyword(text)) {
            members.filter { it.id != speakerId }
        } else {
            targeted
        }
    }
    if (mentioned.isEmpty()) return
    var insertAt = currentIndex + 1
    for (member in mentioned) {
        // Only automatic Bot relays are suppressed; existing user-addressed slots stay intact.
        if (member.id in delegatedBotIds) continue
        if ((turns[member.id] ?: 0) >= capFor(member, group)) continue
        val waiting = slots.withIndex().firstOrNull { (at, slot) ->
            at > currentIndex && slot.botId == member.id && slot.status == GroupSlotStatus.WAITING
        }
        val slot = if (waiting == null) {
            GroupRoundSlot(member.id, member.name, rootWorkId = slots.getOrNull(currentIndex)?.rootWorkId)
        } else {
            slots.removeAt(waiting.index)
        }
        slot.trigger = text
        slot.calledBy = speakerId
        slots.add(insertAt.coerceAtMost(slots.size), slot)
        insertAt += 1
    }
}

internal fun buildRoundTrigger(
    group: GroupChat,
    bot: Bot,
    slot: GroupRoundSlot,
    slots: List<GroupRoundSlot>,
    index: Int,
    roundTrigger: String,
): String = buildString {
    appendLine("【群聊轮次 · ${index + 1}/${slots.size}】")
    appendLine("发言顺序：${formatSpeakingOrder(slots)}")
    appendLine(
        "你是 ${bot.name}。" +
            if (index == 0) "本轮由你先说。" else "前面 $index 位已经发言。",
    )
    appendLine(
        if (index == slots.lastIndex) "你是最后一位，说完本轮结束。"
        else "之后还有 ${slots.size - index - 1} 位会看到你的发言。",
    )
    appendLine("只回应群里的最新消息；没有新的补充、或这条消息不是找你的，只回复 [PASS]。")
    appendLine("不要复述已有结论，不要发感谢、确认或进度回声。需要别人接手时用 @名字；需要多步执行就直接做，完成后给出结果。")
    if (slot.botId == group.adminBotId) appendLine("你是本群管理员：可以 @ 其他成员接手；管理员每轮最多发言 4 次。")
    appendLine()
    appendLine("【本次触发】")
    appendLine(slot.trigger ?: roundTrigger)
}

/** Keep the order line bounded when mentions keep inserting speakers into a long round. */
internal fun formatSpeakingOrder(
    slots: List<GroupRoundSlot>,
    maxEntries: Int = SPEAKING_ORDER_MAX,
): String {
    if (slots.size <= maxEntries) {
        return slots.joinToString(" → ") { it.name + statusSuffix(it.status) }
    }
    val preview = slots.take(maxEntries).joinToString(" → ") { it.name + statusSuffix(it.status) }
    return "$preview → …（共 ${slots.size} 位）"
}

internal fun capFor(bot: Bot, group: GroupChat): Int =
    if (bot.id == group.adminBotId) ADMIN_TURNS_EACH else MEMBER_TURNS_EACH

private fun statusSuffix(status: GroupSlotStatus): String = when (status) {
    GroupSlotStatus.SPEAKING -> "（你，现在）"
    GroupSlotStatus.POSTED -> "（已发言）"
    GroupSlotStatus.PASSED -> "（已跳过）"
    GroupSlotStatus.WORKING -> "（执行中，完成后回报）"
    GroupSlotStatus.FAILED -> "（失败）"
    GroupSlotStatus.LEFT -> "（已离开）"
    GroupSlotStatus.WAITING -> ""
}

private const val MEMBER_TURNS_EACH = 2
private const val ADMIN_TURNS_EACH = 4
private const val SPEAKING_ORDER_MAX = 12
