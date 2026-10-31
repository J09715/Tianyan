package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Give every group message a stable seq and persist each member's read cursor. */
val MIGRATION_67_68 = object : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
        renumberGroupMessages(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_seq` ON `group_messages` (`groupId`, `seq`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_seen` (
                `groupId` TEXT NOT NULL,
                `botId` TEXT NOT NULL,
                `lastSeenSeq` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`, `botId`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_seen_botId` ON `group_seen` (`botId`)")
    }
}
