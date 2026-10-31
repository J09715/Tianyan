package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.*

val MIGRATION_77_78 = object : Migration(77, 78) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bot_member_groups ADD COLUMN definitionJson TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE group_chats ADD COLUMN teamGroupId TEXT DEFAULT NULL")
        db.query("SELECT id,name,adminBotId,createdAt FROM group_chats").use { groups ->
            while (groups.moveToNext()) {
                val chatId = groups.getString(0)
                var teamId = "migrated:$chatId"
                var suffix = 0
                while (db.query("SELECT 1 FROM bot_member_groups WHERE id = ?", arrayOf(teamId)).use { it.moveToFirst() }) {
                    teamId = "migrated:$chatId:${++suffix}"
                }
                val supervisors = linkedMapOf<String, String>()
                db.query("SELECT botId,supervisorBotId FROM group_members WHERE groupId = ?", arrayOf(chatId)).use { members ->
                    while (members.moveToNext()) if (!members.isNull(1)) supervisors[members.getString(0)] = members.getString(1)
                }
                val governance = db.query("SELECT payload FROM group_governance WHERE groupId = ?", arrayOf(chatId)).use {
                    if (it.moveToFirst()) Json.decodeFromString<GroupGovernance>(it.getString(0)) else GroupGovernance()
                }
                val definition = BotTeamDefinition(groups.getString(2), supervisors, governance)
                db.execSQL("INSERT INTO bot_member_groups(id,name,createdAt,definitionJson) VALUES (?,?,?,?)",
                    arrayOf<Any?>(teamId, groups.getString(1), groups.getLong(3), Json.encodeToString(definition)))
                db.execSQL("INSERT INTO bot_member_group_members(memberGroupId,botId,displayOrder,addedAt) SELECT ?,botId,displayOrder,joinedAt FROM group_members WHERE groupId = ?",
                    arrayOf(teamId, chatId))
                db.execSQL("UPDATE group_chats SET teamGroupId = ? WHERE id = ?", arrayOf(teamId, chatId))
                db.execSQL("UPDATE group_members SET supervisorBotId = NULL WHERE groupId = ?", arrayOf(chatId))
                db.query("SELECT id,version,payload FROM group_proposals WHERE groupId = ?", arrayOf(chatId)).use { proposals ->
                    while (proposals.moveToNext()) {
                        val proposal = Json.decodeFromString<GroupProposal>(proposals.getString(2)).copy(teamGroupId = teamId)
                        db.execSQL("UPDATE group_proposals SET payload = ? WHERE id = ? AND version = ?",
                            arrayOf<Any?>(Json.encodeToString(proposal), proposals.getString(0), proposals.getInt(1)))
                    }
                }
                db.execSQL("DELETE FROM group_governance WHERE groupId = ?", arrayOf(chatId))
            }
        }
    }
}
