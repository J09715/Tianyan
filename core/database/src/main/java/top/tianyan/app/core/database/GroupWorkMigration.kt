package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Legacy tasks remain unbound; never guess business identity from task text or paths. */
val MIGRATION_82_83 = object : Migration(82, 83) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE group_chats ADD COLUMN executionLimitsJson TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN groupWorkId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN groupId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN groupProgressJson TEXT DEFAULT NULL")
        listOf("rootWorkId", "stageId", "workItemId").forEach {
            db.execSQL("ALTER TABLE bot_assignments ADD COLUMN $it TEXT DEFAULT NULL")
        }
        db.execSQL("CREATE UNIQUE INDEX index_bot_assignments_groupId_rootWorkId_stageId_workItemId ON bot_assignments(groupId, rootWorkId, stageId, workItemId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS assignment_dispatch_receipts (operationId TEXT NOT NULL, assignmentId TEXT NOT NULL, groupId TEXT NOT NULL, botId TEXT NOT NULL, nativeTaskId TEXT, PRIMARY KEY(operationId, assignmentId), FOREIGN KEY(assignmentId) REFERENCES bot_assignments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_assignment_dispatch_receipts_assignmentId ON assignment_dispatch_receipts(assignmentId)")
        db.execSQL("CREATE INDEX index_assignment_dispatch_receipts_groupId_operationId ON assignment_dispatch_receipts(groupId, operationId)")
        db.execSQL("CREATE INDEX index_assignment_dispatch_receipts_groupId_nativeTaskId ON assignment_dispatch_receipts(groupId, nativeTaskId)")
    }
}
