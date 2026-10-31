package top.tianyan.app.harness.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.model.bot.BotAssignmentRequest

internal class AssignmentRequestPump(
    private val repository: BotAssignmentRepository,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val execute: suspend (BotAssignmentRequest) -> Unit,
) {
    private val lock = Mutex()
    private var collector: Job? = null
    private var recovered = false
    private val active = mutableSetOf<String>()
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    suspend fun start() = lock.withLock {
        if (collector?.isActive == true) return@withLock
        if (!recovered) { repository.recoverRequests(); recovered = true }
        collector = scope.launch {
            merge(repository.pendingRequests(), refresh.receiveAsFlow().map { repository.pendingRequests().first() })
                .retryWhen { failure, _ ->
                    if (failure is CancellationException) throw failure
                    logger.e("[BotAssignment] 读取补充要求失败", failure)
                    delay(5_000)
                    true
                }.collect { requests ->
                    requests.distinctBy { it.assignmentId }.forEach { request ->
                        if (synchronized(active) { active.add(request.assignmentId) }) {
                            scope.launch {
                                try { execute(request) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { logger.e("[BotAssignment] 补充要求处理失败，保留记录供核对", failure) }
                            }.invokeOnCompletion {
                                synchronized(active) { active.remove(request.assignmentId) }
                                refresh.trySend(Unit)
                            }
                        }
                    }
                }
        }
    }
}
