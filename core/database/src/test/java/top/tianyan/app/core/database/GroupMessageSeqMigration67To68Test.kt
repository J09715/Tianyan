package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupMessageSeqMigration67To68Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migration backfills seq per group and creates seen cursors`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("group-seq-67-68.db").absolutePath
        helper.createDatabase(path, 67).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed',NULL,NULL,'dm',0,'#000000',1,1,NULL)""",
            )
            db.execSQL("INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background) VALUES ('group','Team','bot','',1,'default')")
            listOf("m1", "m2", "m3").forEachIndexed { index, id ->
                db.execSQL(
                    """INSERT INTO group_messages
                    (id,groupId,senderId,senderName,senderType,content,timestamp,isStreaming,delegatedTaskId,
                     attachmentsJson,reactionsJson,stepsJson,reasoning)
                    VALUES ('$id','group','user','我','USER','message $index',${index + 1},0,NULL,'[]','[]','[]','')""",
                )
            }
        }
        helper.runMigrationsAndValidate(path, 68, true, MIGRATION_67_68).use { db ->
            db.query("SELECT id, seq FROM group_messages WHERE groupId = 'group' ORDER BY seq").use { cursor ->
                val ids = mutableListOf<String>()
                val seqs = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    ids += cursor.getString(0)
                    seqs += cursor.getLong(1)
                }
                assertEquals(listOf("m1", "m2", "m3"), ids)
                assertEquals(listOf(1L, 2L, 3L), seqs)
            }
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(group_seen)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.containsAll(setOf("groupId", "botId", "lastSeenSeq", "updatedAt")))
            db.execSQL("INSERT INTO group_seen (groupId, botId, lastSeenSeq, updatedAt) VALUES ('group','bot',3,1)")
            db.query("SELECT lastSeenSeq FROM group_seen WHERE groupId = 'group' AND botId = 'bot'").use {
                assertTrue(it.moveToFirst())
                assertEquals(3L, it.getLong(0))
            }
        }
    }
}
