package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Group-level switch between admin-first and all-members replies. */
val MIGRATION_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_chats` ADD COLUMN `replyMode` TEXT NOT NULL DEFAULT 'ADMIN_FIRST'")
    }
}
