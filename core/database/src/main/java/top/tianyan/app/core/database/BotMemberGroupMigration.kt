package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Bot 成员分组：可复用的自定义命名成员集合，供建群时按组选人。 */
val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_member_groups` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_member_group_members` (
                `memberGroupId` TEXT NOT NULL,
                `botId` TEXT NOT NULL,
                `displayOrder` INTEGER NOT NULL,
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`memberGroupId`, `botId`),
                FOREIGN KEY(`memberGroupId`) REFERENCES `bot_member_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bot_member_group_members_memberGroupId` " +
                "ON `bot_member_group_members` (`memberGroupId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bot_member_group_members_botId` " +
                "ON `bot_member_group_members` (`botId`)",
        )
    }
}
