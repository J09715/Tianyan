package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replace read-max-then-insert seq assignment with a per-group counter.
 * Existing seq values are renumbered per group, so seen cursors are reset.
 */
val MIGRATION_70_71 = object : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        renumberGroupMessages(db)
        db.execSQL("DROP INDEX IF EXISTS `index_group_messages_groupId_seq`")
        db.execSQL("CREATE UNIQUE INDEX `index_group_messages_groupId_seq` ON `group_messages` (`groupId`, `seq`)")
        db.execSQL("DELETE FROM `group_seen`")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_message_seq` (
                `groupId` TEXT NOT NULL,
                `nextSeq` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "INSERT INTO `group_message_seq` (`groupId`, `nextSeq`) " +
                "SELECT `groupId`, COALESCE(MAX(`seq`), 0) FROM `group_messages` GROUP BY `groupId`",
        )
    }
}
