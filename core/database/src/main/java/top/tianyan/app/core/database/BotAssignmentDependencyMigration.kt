package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persist assign-task depends_on edges for real dependency visualization. */
val MIGRATION_65_66 = object : Migration(65, 66) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `bot_assignments` ADD COLUMN `dependsOnJson` TEXT NOT NULL DEFAULT '[]'",
        )
    }
}
