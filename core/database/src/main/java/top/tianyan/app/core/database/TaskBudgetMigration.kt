package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_75_76 = object : Migration(75, 76) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE group_chats ADD COLUMN taskBudgetTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bot_assignments ADD COLUMN budgetId TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS bot_task_budgets (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, title TEXT NOT NULL, limitTokens INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(groupId) REFERENCES group_chats(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_task_budgets_groupId ON bot_task_budgets(groupId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS bot_budget_requests (id TEXT NOT NULL PRIMARY KEY, budgetId TEXT NOT NULL, sessionId TEXT NOT NULL, tokens INTEGER NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(budgetId) REFERENCES bot_task_budgets(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_budget_requests_budgetId ON bot_budget_requests(budgetId)")
    }
}
