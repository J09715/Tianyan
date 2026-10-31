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
class GroupSeqCounterMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migrates`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("group-seq-counter-70-71.db").absolutePath
        helper.createDatabase(path, 70).use { db ->
            db.execSQL(
                """INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed',NULL,NULL,'dm',0,'#000000',1,1,NULL)""",
            )
            db.execSQL(
                "INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background,replyMode) " +
                    "VALUES ('group','Team','bot','',1,'default','ADMIN_FIRST')",
            )
            db.execSQL(
                "INSERT INTO group_chats (id,name,adminBotId,description,createdAt,background,replyMode) " +
                    "VALUES ('group2','Team 2','bot','',1,'default','ADMIN_FIRST')",
            )
            // Rows interleave across groups so renumbering must reset the counter per group.
            listOf("m1" to "group", "m2" to "group2", "m3" to "group", "m4" to "group2")
                .forEachIndexed { index, (id, groupId) ->
                    db.execSQL(
                        """INSERT INTO group_messages
                        (id,groupId,senderId,senderName,senderType,content,timestamp,isStreaming,delegatedTaskId,
                         attachmentsJson,reactionsJson,stepsJson,reasoning,seq)
                        VALUES ('$id','$groupId','user','我','USER','message $index',${index + 1},0,NULL,'[]','[]','[]','',0)""",
                    )
                }
            db.execSQL("INSERT INTO group_seen (groupId, botId, lastSeenSeq, updatedAt) VALUES ('group','bot',2,1)")
        }
        helper.runMigrationsAndValidate(path, 71, true, MIGRATION_70_71).use { db ->
            db.query("SELECT id, seq FROM group_messages WHERE groupId = 'group' ORDER BY rowid").use { cursor ->
                val ids = mutableListOf<String>()
                val seqs = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    ids += cursor.getString(0)
                    seqs += cursor.getLong(1)
                }
                assertEquals(listOf("m1", "m3"), ids)
                assertEquals(listOf(1L, 2L), seqs)
            }
            db.query("SELECT id, seq FROM group_messages WHERE groupId = 'group2' ORDER BY rowid").use { cursor ->
                val ids = mutableListOf<String>()
                val seqs = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    ids += cursor.getString(0)
                    seqs += cursor.getLong(1)
                }
                assertEquals(listOf("m2", "m4"), ids)
                assertEquals(listOf(1L, 2L), seqs)
            }
            db.query("SELECT groupId, nextSeq FROM group_message_seq ORDER BY groupId").use { cursor ->
                val next = mutableMapOf<String, Long>()
                while (cursor.moveToNext()) next[cursor.getString(0)] = cursor.getLong(1)
                assertEquals(mapOf("group" to 2L, "group2" to 2L), next)
            }
            db.query("SELECT COUNT(*) FROM group_seen").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            var unique = false
            db.query("PRAGMA index_list('group_messages')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "index_group_messages_groupId_seq") {
                        unique = cursor.getInt(uniqueIndex) == 1
                    }
                }
            }
            assertTrue("seq index must be unique", unique)
        }
    }
}
