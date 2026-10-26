package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v81（借鉴 DSH）：审批审计台账表 + 会话条目 payload 格式版本列；无数据回填。
 *
 * - agent_approval_audits 只登记 agent_approval_requests 覆盖不到的审批事实
 *   （会话授权写入 / 豁免执行 / 子代理 deferred），见表内注释；
 * - harness_entries.payloadVersion 旧行回填 1（= 当前格式），写入方此后恒落当前版本，
 *   读取方对更高版本 fail-closed、更低版本走相邻迁移链。
 */
val MIGRATION_80_81 = object : Migration(80, 81) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS agent_approval_audits (id TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "kind TEXT NOT NULL, toolName TEXT NOT NULL, toolCallId TEXT DEFAULT NULL, " +
                "riskLevel TEXT NOT NULL DEFAULT '', grantKey TEXT DEFAULT NULL, " +
                "approvalRequestId TEXT DEFAULT NULL, detail TEXT DEFAULT NULL, createdAt INTEGER NOT NULL, " +
                "PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_approval_audits_sessionId_createdAt ON agent_approval_audits(sessionId, createdAt)")
        db.execSQL("ALTER TABLE harness_entries ADD COLUMN payloadVersion INTEGER NOT NULL DEFAULT 1")
    }
}
