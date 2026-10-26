package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v80：旁路问答（/btw）记录表、群统一审批模式列、Bot 级 MCP 能力白名单列；无数据回填。 */
val MIGRATION_79_80 = object : Migration(79, 80) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS btw_queries (id TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'RUNNING', " +
                "error TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, finishedAt INTEGER DEFAULT NULL, " +
                "modelId TEXT DEFAULT NULL, providerId TEXT DEFAULT NULL, inputTokens INTEGER NOT NULL DEFAULT 0, " +
                "outputTokens INTEGER NOT NULL DEFAULT 0, cachedTokens INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_btw_queries_sessionId ON btw_queries(sessionId)")
        db.execSQL("ALTER TABLE group_chats ADD COLUMN unifiedApprovalMode TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE persona_bots ADD COLUMN capabilityServerNames TEXT DEFAULT NULL")
    }
}
