package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persist in-flight rounds and late-reply watchers so a restart can resume or close them. */
val MIGRATION_69_70 = object : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_rounds` (
                `id` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `trigger` TEXT NOT NULL,
                `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
                `slotsJson` TEXT NOT NULL DEFAULT '[]',
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_rounds_groupId_status_createdAt` ON `group_rounds` (`groupId`, `status`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_rounds_status` ON `group_rounds` (`status`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_late_replies` (
                `id` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `workerId` TEXT NOT NULL,
                `workerName` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `placeholderId` TEXT NOT NULL,
                `callerBotId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `error` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`workerId`) REFERENCES `persona_bots`(`id`) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_late_replies_groupId_status` ON `group_late_replies` (`groupId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_late_replies_status` ON `group_late_replies` (`status`)")
    }
}
