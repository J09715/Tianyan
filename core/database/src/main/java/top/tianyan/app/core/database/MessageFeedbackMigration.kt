package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v82：单条 assistant 消息的用户反馈表（借鉴 DSH message-feedback）；无数据回填。 */
val MIGRATION_81_82 = object : Migration(81, 82) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS agent_message_feedback (id TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "messageId TEXT NOT NULL, rating TEXT NOT NULL, note TEXT DEFAULT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_message_feedback_messageId ON agent_message_feedback(messageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_message_feedback_sessionId_updatedAt ON agent_message_feedback(sessionId, updatedAt)")
    }
}
