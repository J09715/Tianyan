package top.tianyan.app.harness.groupchat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupRound
import top.tianyan.app.core.model.bot.GroupRoundSlot
import top.tianyan.app.core.model.bot.GroupRoundStatus
import top.tianyan.app.core.model.bot.GroupSlotStatus

internal fun interface GroupRoundResponder {
    suspend fun respond(
        group: GroupChat,
        bot: Bot,
        trigger: String,
        history: String,
        members: List<Bot>,
        imageUrls: List<String>,
        lateReplyCallerId: String?,
        onSubmitted: suspend () -> Unit,
        onLateReply: suspend (String) -> Unit,
    ): GroupTurnOutcome
}

/**
 * 群聊一轮一次只有一位成员发言。用户消息与 @ 接力共用同一个按群串行的轮次；
 * 每个成员只读到自己的 seen 之后的新消息，回复 [PASS] 时删除占位、不打扰群。
 * 轮次与发言槽位落库，进程重启后从第一个 WAITING 槽位继续。
 */
internal class GroupRoundCoordinator(
    private val groups: GroupChatRepository,
    private val queue: GroupReplyQueue,
    private val responder: GroupRoundResponder,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val delegatedTargets: suspend (String, String?) -> Set<String> = { _, _ -> emptySet() },
) {
    private val groupLocks = ConcurrentHashMap<String, Mutex>()
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()
    private val lateRounds = ConcurrentHashMap<String, Int>()
    private val cancelledGroups = ConcurrentHashMap.newKeySet<String>()

    /** 接力达上限而暂停的群：后台迟到回报不再自动开轮，用户新消息后恢复。 */
    private val relayPausedGroups = ConcurrentHashMap.newKeySet<String>()
    private val jobsGuard = Any()

    fun resetLateRounds(groupId: String) {
        lateRounds.remove(groupId)
        cancelledGroups.remove(groupId)
        relayPausedGroups.remove(groupId)
    }

    fun newRound(
        group: GroupChat,
        responders: List<Bot>,
        trigger: String,
        imageUrls: List<String> = emptyList(),
        rootWorkId: String? = null,
    ): GroupRound? {
        if (responders.isEmpty()) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        return GroupRound(
            id = id,
            groupId = group.id,
            trigger = trigger,
            imageUrls = imageUrls,
            slots = responders.map { GroupRoundSlot(it.id, it.name, rootWorkId = rootWorkId ?: id) },
            status = GroupRoundStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Run a round whose row was already committed together with the user message. */
    fun launchPrepared(round: GroupRound, prepare: suspend () -> GroupRound? = { round }) {
        if (round.slots.isEmpty()) return
        launchRound(round.groupId) {
            groupLocks.getOrPut(round.groupId) { Mutex() }.withLock {
                val prepared = prepare() ?: return@withLock
                runRound(prepared, prepared.slots)
            }
        }
    }

    fun withAttachments(round: GroupRound, trigger: String, imageUrls: List<String>): GroupRound =
        round.copy(
            trigger = trigger,
            imageUrls = imageUrls,
            updatedAt = System.currentTimeMillis(),
        )

    suspend fun abandon(round: GroupRound) {
        finishRound(round, round.slots, GroupRoundStatus.CANCELLED)
    }

    /** Late rounds have no user message; commit their row before starting them. */
    suspend fun start(group: GroupChat, responders: List<Bot>, trigger: String, imageUrls: List<String> = emptyList(), rootWorkId: String? = null) {
        val round = newRound(group, responders, trigger, imageUrls, rootWorkId) ?: return
        groups.insertRound(round)
        launchPrepared(round)
    }

    suspend fun recover() {
        val now = System.currentTimeMillis()
        val retention = ROUND_RETENTION_MS
        try {
            groups.pruneFinished(now - retention, now - retention)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupRound] 清理旧轮次失败: ${failure.message}")
        }
        val rounds = try {
            groups.runningRounds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupRound] 读取待恢复轮次失败: ${failure.message}")
            return
        }
        rounds.forEach { round ->
            if (now - round.updatedAt > ROUND_STALE_MS) {
                finishRound(round, round.slots, GroupRoundStatus.CANCELLED)
                return@forEach
            }
            val group = groups.getGroup(round.groupId)
            if (group == null) {
                finishRound(round, round.slots, GroupRoundStatus.CANCELLED)
                return@forEach
            }
            launchRound(group.id) {
                groupLocks.getOrPut(group.id) { Mutex() }.withLock {
                    runRound(round, round.slots)
                }
            }
        }
    }

    suspend fun cancel(groupId: String) {
        cancelledGroups += groupId
        jobs[groupId]?.toList().orEmpty().forEach { it.cancel() }
        try {
            groups.cancelRounds(groupId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.w("[GroupRound] 取消轮次失败: ${failure.message}")
        }
    }

    private suspend fun runRound(round: GroupRound, initialSlots: List<GroupRoundSlot>) {
        val groupId = round.groupId
        var group = groups.getGroup(groupId) ?: return
        var members = groups.getMembers(groupId)
        if (members.isEmpty()) return
        val slots = initialSlots.toMutableList()
        if (slots.isEmpty()) {
            finishRound(round, slots, GroupRoundStatus.DONE)
            return
        }
        val turns = mutableMapOf<String, Int>()
        slots.forEach { slot ->
            if (slot.status != GroupSlotStatus.WAITING && slot.status != GroupSlotStatus.LEFT) {
                turns[slot.botId] = (turns[slot.botId] ?: 0) + 1
            }
        }
        var index = 0
        while (index < slots.size && index < ROUND_MAX_TOTAL_TURNS) {
            group = groups.getGroup(groupId) ?: return
            members = groups.getMembers(groupId)
            val slot = slots[index]
            if (slot.status != GroupSlotStatus.WAITING && slot.status != GroupSlotStatus.SPEAKING) {
                index += 1
                continue
            }
            if (slot.status == GroupSlotStatus.SPEAKING) {
                // A restart cut off the turn that was speaking; its placeholder was closed by recovery.
                slot.status = GroupSlotStatus.FAILED
                persist(round, slots)
                index += 1
                continue
            }
            val bot = members.firstOrNull { it.id == slot.botId }
            if (bot == null) {
                slot.status = GroupSlotStatus.LEFT
                persist(round, slots)
                index += 1
                continue
            }
            if ((turns[bot.id] ?: 0) >= capFor(bot, group)) {
                slot.status = GroupSlotStatus.PASSED
                persist(round, slots)
                index += 1
                continue
            }
            slot.status = GroupSlotStatus.SPEAKING
            persist(round, slots)
            val seen = groups.getSeenSeq(groupId, bot.id)
            val messages = groups.observeMessages(groupId).first()
            val seenSeq = messages.maxOfOrNull { it.seq } ?: 0
            val history = formatRoundHistory(group, messages, seen)
            val trigger = buildRoundTrigger(group, bot, slot, slots, index, round.trigger)
            val outcome = try {
                withContext(GroupWorkContext(slot.rootWorkId ?: round.id, groupId)) {
                    queue.withLock(groupId, "${bot.id}:$groupId") {
                        responder.respond(
                            group,
                            bot,
                            trigger,
                            history,
                            members,
                            round.imageUrls,
                            slot.calledBy,
                            onSubmitted = { groups.setSeenSeq(groupId, bot.id, seenSeq) },
                        ) { text ->
                            lateReply(groupId, slot, text, slot.rootWorkId ?: round.id)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.e("[GroupRound] ${bot.name} 发言失败", failure)
                GroupTurnOutcome("❌ 回复失败：${failure.message}", failed = true)
            }
            turns[bot.id] = (turns[bot.id] ?: 0) + 1
            when {
                outcome.working -> slot.status = GroupSlotStatus.WORKING
                outcome.passed -> slot.status = GroupSlotStatus.PASSED
                outcome.failed -> slot.status = GroupSlotStatus.FAILED
                else -> {
                    slot.status = GroupSlotStatus.POSTED
                    appendRoundMentions(slots, index, outcome.text, members, turns, group,
                        delegatedTargets(groupId, outcome.nativeTaskId))
                }
            }
            persist(round, slots)
            index += 1
        }
        var skipped = false
        var relayOverflow = false
        for (remaining in index until slots.size) {
            if (slots[remaining].status == GroupSlotStatus.WAITING) {
                slots[remaining].status = GroupSlotStatus.PASSED
                skipped = true
                if (slots[remaining].calledBy != null) relayOverflow = true
            }
        }
        if (skipped) persist(round, slots)
        finishRound(round, slots, GroupRoundStatus.DONE)
        // 借鉴 DS Bot 的接力暂停制：@ 接力还在继续却撞到轮次总上限时，不硬截断后静默结束，
        // 而是暂停群（迟到回报不再自动开轮）并留一行说明；用户发新消息即恢复（resetLateRounds）。
        if (relayOverflow && groupId !in cancelledGroups && relayPausedGroups.add(groupId)) {
            runCatching {
                groups.postMessage(
                    top.tianyan.app.core.model.bot.GroupMessage(
                        id = UUID.randomUUID().toString(),
                        groupId = groupId,
                        senderId = "system",
                        senderName = "系统通知",
                        senderType = top.tianyan.app.core.model.bot.GroupSenderType.SYSTEM,
                        content = "🛑 @ 接力已达轮次上限，群聊暂停；发送新消息即可继续。",
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { logger.w("[GroupRound] 接力暂停通知失败: ${it.message}") }
        }
    }

    private suspend fun persist(round: GroupRound, slots: List<GroupRoundSlot>) {
        val updated = round.copy(slots = slots.toList(), updatedAt = System.currentTimeMillis())
        groups.updateRoundProgress(updated)
    }

    private suspend fun finishRound(
        round: GroupRound,
        slots: List<GroupRoundSlot>,
        status: GroupRoundStatus,
    ) {
        val updated = round.copy(
            slots = slots.toList(),
            status = status,
            updatedAt = System.currentTimeMillis(),
        )
        groups.finishRound(updated)
    }

    private fun lateReply(groupId: String, slot: GroupRoundSlot, text: String, rootWorkId: String) {
        lateReplyRecovered(groupId, slot.botId, slot.name, slot.calledBy, text, rootWorkId)
    }

    fun lateReplyRecovered(
        groupId: String,
        workerId: String,
        workerName: String,
        callerBotId: String?,
        text: String,
        rootWorkId: String? = null,
    ) {
        if (groupId in cancelledGroups) return
        if (groupId in relayPausedGroups) return
        val callerId = callerBotId ?: return
        if (callerId == workerId) return
        val count = lateRounds.getOrDefault(groupId, 0)
        if (count >= MAX_LATE_ROUNDS) return
        lateRounds[groupId] = count + 1
        launchRound(groupId) {
            val group = groups.getGroup(groupId) ?: return@launchRound
            val caller = groups.getMembers(groupId).firstOrNull { it.id == callerId } ?: return@launchRound
            start(
                group,
                listOf(caller),
                "【迟到回复】$workerName 完成了你交给它的工作：\n${text}",
                rootWorkId = rootWorkId,
            )
        }
    }

    private fun launchRound(groupId: String, block: suspend () -> Unit) {
        val job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.e("[GroupRound] 轮次执行失败", failure)
            }
        }
        track(groupId, job)
    }

    private fun track(groupId: String, job: Job) {
        synchronized(jobsGuard) {
            jobs.getOrPut(groupId) { ConcurrentHashMap.newKeySet() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(jobsGuard) {
                jobs[groupId]?.let { set ->
                    set.remove(job)
                    if (set.isEmpty()) jobs.remove(groupId, set)
                }
            }
        }
    }

    private companion object {
        const val MAX_LATE_ROUNDS = 10
        const val ROUND_STALE_MS = 24L * 60 * 60 * 1000
        const val ROUND_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
