package top.tianyan.app.harness.checkpoint

import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.session.SessionTreeStore

/**
 * 轮文件改动卡片（借鉴 DSH deliverables 的轮始/末快照对比）。
 *
 * 数据源是 CheckpointStore 的轮快照：每个被 write/edit 触碰的路径都带着
 * 「轮初内容」与「本轮最后一次成功写入后内容」两个凭据，二者对比即改动分类。
 * 卡片消息是 UI-only（持久化进会话树、不进模型上下文）；先落库再上屏，
 * id 确定性派生自会话与轮号（同 id 同 payload 由存储层幂等复用）。
 *
 * 发布时机（两个互补挂点，见 HarnessLoop）：
 * - 轮正常完成（Completed）→ 从活动轮快照即时发布；
 * - 下一轮用户输入 beginTurn 关闭上一轮 → 补发（覆盖停止/失败/审批过期后未发的轮）。
 * 补发前先查会话历史按轮号判重，保证一轮至多一张卡片（事件含发布时间戳，
 * 字节级幂等不成立，判重必须在发布前完成）。
 */
object TurnChangeCards {

    /**
     * 从轮快照计算改动分类。删除类不存在：天衍的 checkpoint 只覆盖写工具，
     * shell 删除不经 capture（卡片如实只反映写工具可见的改动）。
     * 轮初与最后写入内容完全一致的路径视为无变化，不进任何类。
     */
    fun classify(files: List<FileSnap>): Triple<List<String>, List<String>, List<String>> {
        val created = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val touched = mutableListOf<String>()
        for (snap in files) {
            when {
                snap.content == null && snap.afterContent != null -> created += snap.path
                snap.content != null && snap.afterContent != null ->
                    if (snap.content != snap.afterContent) modified += snap.path
                else -> touched += snap.path
            }
        }
        return Triple(created, modified, touched)
    }

    fun entryId(sessionId: String, turn: Int): String = "turn_changes_${sessionId}_$turn"

    /** 落库并上屏一轮的改动卡片；分类全空时不发布。 */
    suspend fun publish(
        sessionId: String,
        turn: Int,
        files: List<FileSnap>,
        store: SessionTreeStore,
        projector: SessionMessageProjector,
    ) {
        val (created, modified, touched) = classify(files)
        if (created.isEmpty() && modified.isEmpty() && touched.isEmpty()) return
        val event = TurnChangesEvent(
            id = entryId(sessionId, turn),
            createdAt = System.currentTimeMillis(),
            turn = turn,
            created = created,
            modified = modified,
            touched = touched,
        )
        store.append(sessionId, event)
        projector.publishPersisted(sessionId, event)
    }

    /** 轮完成时从活动轮快照即时发布；无活动轮/空轮返回 false。 */
    suspend fun publishActiveTurn(
        sessionId: String,
        controller: RewindController,
        store: SessionTreeStore,
        projector: SessionMessageProjector,
    ): Boolean {
        val turn = controller.activeTurnNumber(sessionId) ?: return false
        val files = controller.activeTurnFiles(sessionId)
        if (files.isEmpty()) return false
        publish(sessionId, turn, files, store, projector)
        return true
    }

    /**
     * 下一轮开头补发被关闭的上一轮；该轮历史里已有卡片（完成路径发过）则跳过。
     * 历史查询与落库共用 [SessionTreeStore]（卡片必然在活动分支尾部）。
     */
    suspend fun publishClosedTurn(
        sessionId: String,
        closed: Checkpoint?,
        store: SessionTreeStore,
        projector: SessionMessageProjector,
    ) {
        closed ?: return
        val alreadyPublished = store.load(sessionId).any { it is TurnChangesEvent && it.turn == closed.turn }
        if (alreadyPublished) return
        publish(sessionId, closed.turn, closed.files, store, projector)
    }
}
