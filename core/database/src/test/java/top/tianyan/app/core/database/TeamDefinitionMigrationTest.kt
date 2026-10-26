package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamDefinitionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun migratesTeamOwnershipAndApprovals() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("team-definition-migration").absolutePath
        val rule = GroupReviewRule("r", "review", "author", "reviewer", "executor")
        helper.createDatabase(path, 77).use { db ->
            listOf("chief", "author", "reviewer", "executor", "worker").forEach { id ->
                db.execSQL("INSERT INTO persona_bots(id,name,role,systemPrompt,avatarSeed,sessionId,isMainBot,colorHex,createdAt,updatedAt) VALUES (?,?,?,?,?,?,0,'#123456',1,1)", arrayOf(id,id,"role","",id,"dm-$id"))
            }
            db.execSQL("INSERT INTO bot_member_groups(id,name,createdAt) VALUES ('category','旧分类',1)")
            db.execSQL("INSERT INTO group_chats(id,name,adminBotId,description,createdAt,background) VALUES ('g','旧群','chief','',1,'default')")
            listOf("chief", "author", "reviewer", "executor", "worker").forEachIndexed { index, id ->
                db.execSQL("INSERT INTO group_members(groupId,botId,displayOrder,joinedAt,supervisorBotId) VALUES ('g',?,?,1,?)",
                    arrayOf<Any?>(id,index,if (id == "worker") "executor" else null))
            }
            db.execSQL("INSERT INTO group_governance(groupId,payload) VALUES ('g',?)", arrayOf(Json.encodeToString(GroupGovernance(listOf(rule)))))
            db.execSQL("INSERT INTO group_proposals(id,version,groupId,payload) VALUES ('p',1,'g',?)",
                arrayOf(Json.encodeToString(GroupProposal("p","g",rule,1,"approved plan",ProposalDecision.APPROVED,"verified","reviewer"))))
        }
        helper.runMigrationsAndValidate(path, 78, true, MIGRATION_77_78).use { db ->
            val teamId = db.query("SELECT teamGroupId FROM group_chats WHERE id='g'").use { assertTrue(it.moveToFirst()); it.getString(0) }
            db.query("SELECT definitionJson FROM bot_member_groups WHERE id = ?", arrayOf(teamId)).use {
                assertTrue(it.moveToFirst())
                val definition = Json.decodeFromString<BotTeamDefinition>(it.getString(0))
                assertEquals("chief", definition.adminBotId)
                assertEquals("executor", definition.supervisors["worker"])
                assertEquals(listOf(rule), definition.governance.rules)
            }
            db.query("SELECT COUNT(*) FROM bot_member_group_members WHERE memberGroupId = ?", arrayOf(teamId)).use { assertTrue(it.moveToFirst()); assertEquals(5,it.getInt(0)) }
            db.query("SELECT definitionJson FROM bot_member_groups WHERE id='category'").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            db.query("SELECT payload FROM group_proposals WHERE id='p'").use {
                assertTrue(it.moveToFirst()); val proposal = Json.decodeFromString<GroupProposal>(it.getString(0))
                assertEquals(ProposalDecision.APPROVED, proposal.decision); assertEquals(teamId,proposal.teamGroupId)
            }
            db.query("SELECT COUNT(*) FROM group_governance").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM bot_assignment_requests").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        }
    }
}
