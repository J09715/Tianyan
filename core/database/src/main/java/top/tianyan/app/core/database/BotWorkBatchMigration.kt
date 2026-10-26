package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persist work batches so one main-bot turn reports once after all children settle. */
val MIGRATION_66_67 = object : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bot_assignments` ADD COLUMN `batchId` TEXT DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignments_batchId_status` ON `bot_assignments` (`batchId`, `status`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_work_batches` (
                `id` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `requesterBotId` TEXT NOT NULL,
                `requesterSessionId` TEXT NOT NULL,
                `originOperationId` TEXT,
                `revision` INTEGER NOT NULL DEFAULT 0,
                `reportRevision` INTEGER,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `settledAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`requesterBotId`) REFERENCES `persona_bots`(`id`) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_work_batches_groupId_createdAt` ON `bot_work_batches` (`groupId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_work_batches_status_updatedAt` ON `bot_work_batches` (`status`, `updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_work_batches_requesterBotId` ON `bot_work_batches` (`requesterBotId`)")
    }
}
