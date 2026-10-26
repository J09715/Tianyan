package top.tianyan.app.bot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.harness.bot.schedule.BotScheduleRunner
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup

/**
 * Independent timers avoid cancelling active Workers or chaining an earlier deadline behind a later one.
 * Old timers may wake once; durable occurrence claims make these harmless no-op scans.
 */
class BotSchedulePlanner(
    private val context: Context,
    private val scheduleRepository: BotScheduleRepository,
    private val runner: BotScheduleRunner,
) : BotScheduleWakeup {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduledAt: Long? = null

    fun start() {
        scope.launch { runner.recover(); enqueue(force = true) }
    }

    override fun request() {
        enqueue()
    }

    fun afterRun() { enqueue(force = true) }

    private fun enqueue(force: Boolean = false) {
        scope.launch {
            mutex.withLock {
                val target = scheduleRepository.nextWakeAt()
                if (target == null) {
                    scheduledAt = null
                    return@withLock
                }
                if (!force && target == scheduledAt) return@withLock
                val now = System.currentTimeMillis()
                val request = OneTimeWorkRequestBuilder<BotScheduleWorker>()
                    .setInitialDelay((target - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .addTag(TAG)
                    .build()
                // Immediate successors need a fresh identity even while the previous tick is RUNNING.
                val name = if (target > now) "$UNIQUE_NAME:$target" else "$UNIQUE_NAME:due:${UUID.randomUUID()}"
                WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
                scheduledAt = target
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "bot_schedule_tick"
        private const val TAG = "bot_schedule"
    }
}

/** 到点执行全部 Bot 排期，并续订下一次唤醒。 */
class BotScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runner: BotScheduleRunner,
    private val planner: BotSchedulePlanner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = try { runner.runDue(); Result.success() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
        planner.afterRun()
        return result
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
