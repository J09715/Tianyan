package top.tianyan.app.harness

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.tianyan.app.harness.checkpoint.CheckpointStore
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus

/** Shared pre/post-image bookkeeping for workspace backends and downloads. */
class WorkspaceMutationSnapshots(
    private val store: CheckpointStore? = null,
    private val events: HarnessEventBus? = null,
) {
    suspend fun before(sessionId: String, operations: WorkspaceToolOperations, path: String) {
        val normalized = path.trim().trimStart('/')
        val size = operations.fileSizeOrNull(normalized)
        currentCoroutineContext().ensureActive()
        if (size != null && size > CheckpointStore.SNAPSHOT_MAX_BYTES) {
            events?.emit(HarnessEvent.RecoveryApplied(
                sessionId = sessionId, timestamp = System.currentTimeMillis(), operationId = null,
                outcome = "checkpoint_incomplete",
                detail = "文件 $normalized 超过 ${CheckpointStore.SNAPSHOT_MAX_BYTES} bytes，无法完整捕获轮前快照；本轮 rewind 不能保证恢复该文件。",
            ))
            return
        }
        if (store != null) {
            val content = operations.previewOrNull(normalized)
            currentCoroutineContext().ensureActive()
            store.capture(sessionId, normalized, content)
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun after(sessionId: String, operations: WorkspaceToolOperations, path: String, knownContent: String?) {
        if (sessionId.isBlank()) return
        val checkpointStore = store ?: return
        val normalized = path.trim().trimStart('/')
        val size = operations.fileSizeOrNull(normalized)
        currentCoroutineContext().ensureActive()
        if (size == null || size > CheckpointStore.SNAPSHOT_MAX_BYTES) return
        val content = knownContent ?: operations.previewOrNull(normalized)
        currentCoroutineContext().ensureActive()
        if (content != null) checkpointStore.captureAfterImage(sessionId, normalized, content)
    }
}
