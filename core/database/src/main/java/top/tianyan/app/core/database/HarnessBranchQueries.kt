package top.tianyan.app.core.database

import androidx.room.Query

/** Read-only immutable-tree queries shared by the transactional runtime DAO. */
interface HarnessBranchQueries {
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch ORDER BY sequence
    """)
    suspend fun branch(sessionId: String, leafId: String): List<HarnessEntryEntity>

    /**
     * Return only the newest branch window to the Android process. The recursive walk still
     * happens inside SQLite, but payloadJson for older ancestors never enters the managed heap.
     */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM (
            SELECT * FROM branch ORDER BY sequence DESC LIMIT :limit
        ) ORDER BY sequence
    """)
    suspend fun branchTail(sessionId: String, leafId: String, limit: Int): List<HarnessEntryEntity>

    /**
     * 按序列号水位线只返回增量条目与召回块，已被折叠的更早历史祖先条目不会加载到托管堆中。
     */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE sequence >= :minSequence OR entryType = 'recall_context'
        ORDER BY sequence
    """)
    suspend fun branchWindow(sessionId: String, leafId: String, minSequence: Long): List<HarnessEntryEntity>

    /** Latest entry of one type on the selected immutable-tree branch. */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = :entryType
        ORDER BY sequence DESC LIMIT 1
    """)
    suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String,
        entryType: String,
    ): HarnessEntryEntity?

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = 'message' AND payloadJson LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY sequence DESC LIMIT :limit
    """)
    suspend fun searchBranch(sessionId: String, leafId: String, query: String, limit: Int): List<HarnessEntryEntity>

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch WHERE entryType = 'message' ORDER BY sequence LIMIT 1 OFFSET :index
    """)
    suspend fun branchEntryAt(sessionId: String, leafId: String, index: Int): HarnessEntryEntity?

}
