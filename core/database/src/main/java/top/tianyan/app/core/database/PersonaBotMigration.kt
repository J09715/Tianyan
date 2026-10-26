package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 群聊增强：背景风格、消息附件、表情回应与 Bot 工具步骤时间线。 */
val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_chats` ADD COLUMN `background` TEXT NOT NULL DEFAULT 'default'")
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `attachmentsJson` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `reactionsJson` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `stepsJson` TEXT NOT NULL DEFAULT '[]'")
    }
}

/** Bot × 群聊独立会话映射：群聊运行不再复用 Bot 的默认会话。 */
val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_group_sessions` (
                `botId` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`botId`, `groupId`),
                FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_group_sessions_groupId` ON `bot_group_sessions` (`groupId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_group_sessions_sessionId` ON `bot_group_sessions` (`sessionId`)")
    }
}

/**
 * PersonaBot 群聊体系：Bot 名册、群聊、成员、消息与 Bot 定时任务。
 *
 * 树形结构使用 CASCADE，Bot 名册以 RESTRICT 保护仍担任群管理员的 Bot，
 * 由业务层先转移管理员或删除群聊，避免误删后留下无主群。
 */
val MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_assignments` (
                `id` TEXT NOT NULL,
                `groupId` TEXT,
                `botId` TEXT NOT NULL,
                `botName` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `task` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `writePathsJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `finishedAt` INTEGER,
                `summary` TEXT,
                `error` TEXT,
                `sessionId` TEXT,
                `laneName` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignments_groupId_createdAt` ON `bot_assignments` (`groupId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_assignments_status` ON `bot_assignments` (`status`)")
    }
}

/**
 * PersonaBot 群聊体系：Bot 名册、群聊、成员、消息与 Bot 定时任务。
 *
 * 树形结构使用 CASCADE，Bot 名册以 RESTRICT 保护仍担任群管理员的 Bot，
 * 由业务层先转移管理员或删除群聊，避免误删后留下无主群。
 */
val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `persona_bots` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `systemPrompt` TEXT NOT NULL,
                `avatarSeed` TEXT NOT NULL,
                `modelId` TEXT,
                `providerId` TEXT,
                `sessionId` TEXT NOT NULL,
                `isMainBot` INTEGER NOT NULL,
                `colorHex` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_persona_bots_sessionId` ON `persona_bots` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_bots_isMainBot` ON `persona_bots` (`isMainBot`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_chats` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `adminBotId` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`adminBotId`) REFERENCES `persona_bots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chats_adminBotId` ON `group_chats` (`adminBotId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_members` (
                `groupId` TEXT NOT NULL,
                `botId` TEXT NOT NULL,
                `displayOrder` INTEGER NOT NULL,
                `joinedAt` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`, `botId`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_groupId` ON `group_members` (`groupId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_botId` ON `group_members` (`botId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `group_messages` (
                `id` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `senderName` TEXT NOT NULL,
                `senderType` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `isStreaming` INTEGER NOT NULL,
                `delegatedTaskId` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`groupId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_timestamp` ON `group_messages` (`groupId`, `timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_delegatedTaskId` ON `group_messages` (`delegatedTaskId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `bot_schedules` (
                `id` TEXT NOT NULL,
                `botId` TEXT NOT NULL,
                `groupId` TEXT,
                `cron` TEXT NOT NULL,
                `instruction` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `nextRunAt` INTEGER NOT NULL,
                `lastRunAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`botId`) REFERENCES `persona_bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_schedules_botId` ON `bot_schedules` (`botId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_schedules_nextRunAt` ON `bot_schedules` (`nextRunAt`)")
    }
}
