package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_73_74 = object : Migration(73, 74) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mcp_servers ADD COLUMN headersCiphertext TEXT NOT NULL DEFAULT ''")
    }
}
