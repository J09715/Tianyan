package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_76_77 = object : Migration(76, 77) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS group_governance (groupId TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, FOREIGN KEY(groupId) REFERENCES group_chats(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS group_proposals (id TEXT NOT NULL, version INTEGER NOT NULL, groupId TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id, version), FOREIGN KEY(groupId) REFERENCES group_chats(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_group_proposals_groupId ON group_proposals(groupId)")
        db.execSQL("ALTER TABLE bot_assignments ADD COLUMN proposalId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE bot_assignments ADD COLUMN proposalVersion INTEGER DEFAULT NULL")
    }
}
