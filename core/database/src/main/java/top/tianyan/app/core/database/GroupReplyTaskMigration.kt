package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A late reply follows its own durable task, even when its native session runs queued successors. */
val MIGRATION_71_72 = object : Migration(71, 72) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_late_replies` ADD COLUMN `taskId` TEXT DEFAULT NULL")
    }
}
