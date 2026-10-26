package top.tianyan.app.harness.bot.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotScheduleDelivery

fun interface BotScheduledSubmission {
    suspend fun submit(bot: Bot, instruction: String, groupId: String?): Boolean
}

/** Schedules stage durable occurrences before attempting durable Harness admission. */
class BotScheduleRunner(
    private val scheduleRepository: BotScheduleRepository,
    private val botRepository: PersonaBotRepository,
    private val logger: AppLogger,
    private val submit: BotScheduledSubmission,
) {
    private val mutex = Mutex()
    private var recovered = false

    suspend fun recover() = mutex.withLock { recoverOnce() }

    private suspend fun recoverOnce() {
        if (!recovered) {
            scheduleRepository.recoverInterruptedDeliveries()
            recovered = true
        }
    }

    suspend fun runDue(now: Long = System.currentTimeMillis()): Int = mutex.withLock {
        recoverOnce()
        scheduleRepository.listDue(now).take(MAX_DUE_PER_RUN).forEach { schedule ->
            val parsed = runCatching { CronSchedule.parse(schedule.cron).nextAfter(now) }
            if (parsed.isFailure) {
                logger.e("[BotScheduleRunner] 排期 ${schedule.id} 的 cron 表达式无效（${schedule.cron}），已禁用该排期以停止无效重复触发", parsed.exceptionOrNull())
                scheduleRepository.setScheduleEnabled(schedule.id, false)
                return@forEach
            }
            val next = parsed.getOrThrow()
            if (next == null) {
                // 解析成功但两年窗口内没有下一拍（如闰日 cron）。当前 occurrence 仍要投递，
                // nextRunAt 必须用 MAX 离开 listDue；禁用要等 claim 之后，pending/claim 都要求 enabled = 1。
                logger.e("[BotScheduleRunner] 排期 ${schedule.id} 的 cron（${schedule.cron}）在扫描窗口内没有下一次触发，已投递当前 occurrence 并禁用后续调度")
                scheduleRepository.stageDelivery(schedule, now, Long.MAX_VALUE)
                return@forEach
            }
            scheduleRepository.stageDelivery(schedule, now, next)
        }
        var admitted = 0
        scheduleRepository.pendingDeliveries(MAX_DUE_PER_RUN).forEach { delivery ->
            if (!scheduleRepository.claimDelivery(delivery.id)) return@forEach
            try {
                val bot = botRepository.getBot(delivery.botId)
                val accepted = bot != null && submit.submit(bot, delivery.instruction, delivery.groupId)
                scheduleRepository.finishDelivery(
                    delivery.id,
                    if (accepted) BotScheduleDelivery.ADMITTED else BotScheduleDelivery.REJECTED,
                    if (accepted) null else "会话未受理，请检查 Bot、群成员和目标会话",
                )
                disableWhenNoNextOccurrence(delivery.scheduleId)
                if (accepted) admitted++
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    scheduleRepository.finishDelivery(delivery.id, BotScheduleDelivery.UNKNOWN, "投递被中断，请核对会话记录")
                    disableWhenNoNextOccurrence(delivery.scheduleId)
                }
                throw cancelled
            } catch (failure: Exception) {
                // Submission can throw after committing admission. Retrying would risk duplicate effects.
                scheduleRepository.finishDelivery(delivery.id, BotScheduleDelivery.UNKNOWN, "投递结果待核对")
                disableWhenNoNextOccurrence(delivery.scheduleId)
                logger.e("[BotScheduleRunner] 排期 ${delivery.scheduleId} 投递结果未知", failure)
            }
        }
        admitted
    }

    /** 当前 occurrence 已经落库后，把没有下一拍的排期关掉，避免它以 enabled 停在哨兵时刻。 */
    private suspend fun disableWhenNoNextOccurrence(scheduleId: String) {
        if (scheduleRepository.getSchedule(scheduleId)?.nextRunAt == Long.MAX_VALUE) {
            scheduleRepository.setScheduleEnabled(scheduleId, false)
        }
    }

    suspend fun nextRunAt(): Long? = scheduleRepository.nextWakeAt()

    private companion object { const val MAX_DUE_PER_RUN = 8 }
}
