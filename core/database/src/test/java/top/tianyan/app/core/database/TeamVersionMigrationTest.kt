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
class TeamVersionMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Test fun preservesRunningVersionAndApprovals() {
        val path=InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("team-versions").absolutePath
        val rule=GroupReviewRule("r","review","author","reviewer","executor")
        val definition=BotTeamDefinition("chief",emptyMap(),GroupGovernance(listOf(rule)))
        helper.createDatabase(path,78).use { db ->
            listOf("chief","author","reviewer","executor").forEachIndexed { index,id ->
                db.execSQL("INSERT INTO persona_bots(id,name,role,systemPrompt,avatarSeed,sessionId,isMainBot,colorHex,createdAt,updatedAt) VALUES (?,?,?,?,?,?,0,'#123456',1,1)",arrayOf(id,id,"role","",id,"dm-$id"))
            }
            db.execSQL("INSERT INTO bot_member_groups(id,name,createdAt,definitionJson) VALUES ('t','team',1,?)",arrayOf(Json.encodeToString(definition)))
            listOf("chief","author","reviewer","executor").forEachIndexed { index,id ->
                db.execSQL("INSERT INTO bot_member_group_members VALUES ('t',?,?,1)",arrayOf<Any?>(id,index))
            }
            db.execSQL("INSERT INTO group_chats(id,name,adminBotId,description,createdAt,background,teamGroupId) VALUES ('g','group','chief','',1,'default','t')")
            db.execSQL("INSERT INTO bot_assignments(id,groupId,botId,botName,title,task,status,writePathsJson,createdAt,startedAt,independent) VALUES ('task','g','executor','executor','task','work','RUNNING','[]',1,1,1)")
            db.execSQL("INSERT INTO group_proposals VALUES ('p',1,'g',?)",arrayOf(Json.encodeToString(GroupProposal("p","g",rule,1,"plan",ProposalDecision.APPROVED,"verified","reviewer","t"))))
        }
        helper.runMigrationsAndValidate(path,79,true,MIGRATION_78_79).use { db ->
            db.query("SELECT definitionJson,version,memberIdsJson FROM team_versions").use {
                assertTrue(it.moveToFirst());assertEquals(definition,Json.decodeFromString<BotTeamDefinition>(it.getString(0)));assertEquals(1,it.getInt(1))
                assertEquals(4,Json.decodeFromString<List<String>>(it.getString(2)).size)
            }
            db.query("SELECT teamVersion FROM group_chats WHERE id='g'").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
            db.query("SELECT teamBinding,status FROM bot_assignments WHERE id='task'").use { assertTrue(it.moveToFirst());assertEquals("t@1",it.getString(0));assertEquals("RUNNING",it.getString(1)) }
            db.query("SELECT payload FROM group_proposals").use {
                assertTrue(it.moveToFirst());val p=Json.decodeFromString<GroupProposal>(it.getString(0));assertEquals(1,p.teamVersion);assertEquals(ProposalDecision.APPROVED,p.decision)
            }
            db.query("SELECT COUNT(*) FROM bot_assignment_requests").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        }
    }
}
