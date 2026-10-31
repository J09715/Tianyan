package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.GroupDeliveryRepository
import top.tianyan.app.core.model.bot.GroupDelivery

/** Only unsubmitted receipts are replayable. Native admission uncertainty never retries execution. */
internal class GroupDeliveryPump(
    private val repository: GroupDeliveryRepository,
    private val replies: GroupReplyQueue,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val deliver: suspend (GroupDelivery) -> GroupTurnOutcome,
) {
    private val startLock = Mutex()
    private var collector: Job? = null
    private var recovered = false
    private val scheduled = mutableSetOf<String>()
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    suspend fun start() = startLock.withLock {
        if (collector?.isActive == true) return@withLock
        if (!recovered) {
            repository.recoverDeliveries()
            repository.recoverOrphanStreams()
            recovered = true
        }
        collector = scope.launch {
            merge(repository.pendingDeliveries(), refresh.receiveAsFlow().map {
                repository.pendingDeliveries().first()
            }).retryWhen { failure, _ ->
                if (failure is CancellationException) throw failure
                logger.e("[GroupDelivery] 收件读取失败，稍后重试", failure)
                delay(5_000)
                true
            }.collect { receipts ->
                receipts.distinctBy { "${it.botId}:${it.groupId}" }.forEach { receipt ->
                    if (synchronized(scheduled) { scheduled.add("${receipt.botId}:${receipt.groupId}") }) enqueue(receipt)
                }
            }
        }
    }

    private suspend fun enqueue(receipt: GroupDelivery) {
        val job = replies.launch(receipt.groupId, "${receipt.botId}:${receipt.groupId}") {
            try {
                if (!repository.claimDelivery(receipt.id)) return@launch
                val result = deliver(receipt)
                repository.finishDelivery(receipt.id, when {
                    result.uncertain -> GroupDelivery.UNKNOWN
                    result.failed -> GroupDelivery.FAILED
                    else -> GroupDelivery.COMPLETED
                }, result.text.takeIf { result.failed || result.uncertain })
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    markUnknown(receipt.id, "群消息处理被中断，请核对会话记录")
                }
                throw cancelled
            } catch (failure: Exception) {
                logger.e("[GroupDelivery] 群消息处理失败", failure)
                markUnknown(receipt.id, "群消息处理结果待核对，请检查会话记录")
            }
        }
        if (job == null) {
            runCatching { repository.finishDelivery(receipt.id, GroupDelivery.CANCELLED) }
                .onFailure { logger.e("[GroupDelivery] 取消收件记录失败", it) }
            unschedule(receipt)
        } else job.invokeOnCompletion { unschedule(receipt) }
    }

    private fun unschedule(receipt: GroupDelivery) {
        synchronized(scheduled) { scheduled.remove("${receipt.botId}:${receipt.groupId}") }
        refresh.trySend(Unit)
    }

    private suspend fun markUnknown(id: String, reason: String) {
        // A storage outage must not crash the process or trigger a second native submission.
        runCatching { repository.finishDelivery(id, GroupDelivery.UNKNOWN, reason) }
            .onFailure { logger.e("[GroupDelivery] 保存待核对状态失败，保留收件供重启核对", it) }
    }
}
