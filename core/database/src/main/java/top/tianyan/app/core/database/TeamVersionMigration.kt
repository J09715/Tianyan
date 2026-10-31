package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.GroupProposal

val MIGRATION_78_79 = object : Migration(78, 79) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bot_member_groups ADD COLUMN publishedVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bot_member_groups ADD COLUMN draftRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE group_chats ADD COLUMN teamVersion INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE bot_assignments ADD COLUMN teamBinding TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS team_versions (teamId TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, memberIdsJson TEXT NOT NULL, definitionJson TEXT NOT NULL, publishedAt INTEGER NOT NULL, PRIMARY KEY(teamId,version), FOREIGN KEY(teamId) REFERENCES bot_member_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.query("SELECT id,name,definitionJson,createdAt FROM bot_member_groups WHERE definitionJson IS NOT NULL").use { teams ->
            while (teams.moveToNext()) {
                val id = teams.getString(0)
                val members = mutableListOf<String>()
                db.query("SELECT botId FROM bot_member_group_members WHERE memberGroupId = ? ORDER BY displayOrder", arrayOf(id)).use {
                    while (it.moveToNext()) members += it.getString(0)
                }
                db.execSQL("INSERT INTO team_versions VALUES (?,1,?,?,?,?)", arrayOf<Any?>(id, teams.getString(1),
                    Json.encodeToString(members), teams.getString(2), teams.getLong(3)))
                db.execSQL("UPDATE bot_member_groups SET publishedVersion = 1, draftRevision = 1 WHERE id = ?", arrayOf(id))
                db.execSQL("UPDATE group_chats SET teamVersion = 1 WHERE teamGroupId = ?", arrayOf(id))
            }
        }
        db.execSQL("UPDATE bot_assignments SET teamBinding = (SELECT teamGroupId || '@' || teamVersion FROM group_chats WHERE id = bot_assignments.groupId)")
        db.query("SELECT id,version,payload FROM group_proposals").use {
            while (it.moveToNext()) {
                val old = Json.decodeFromString<GroupProposal>(it.getString(2))
                val version = db.query("SELECT teamVersion FROM group_chats WHERE id = ?", arrayOf(old.groupId)).use { group ->
                    if (group.moveToFirst() && !group.isNull(0)) group.getInt(0) else null
                }
                db.execSQL("UPDATE group_proposals SET payload = ? WHERE id = ? AND version = ?",
                    arrayOf<Any?>(Json.encodeToString(old.copy(teamVersion = version)), it.getString(0), it.getInt(1)))
            }
        }
    }
}
