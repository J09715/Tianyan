package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.BackupRecords

/**
 * JsonPrimitive(boolean) on an allowlisted INTEGER column must pass [RoomDatabaseBackupRepository.validate]
 * and restore as SQLite 1/0. A string in that column stays invalid.
 *
 * `agent_skills.isEnabled` is INTEGER NOT NULL (see AgentSkillEntity / schema 54).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseBackupBooleanTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDatabaseBackupRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomDatabaseBackupRepository(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun `boolean true on agent_skills isEnabled validates and restores as 1`() = runBlocking {
        restoreAndAssert(JsonPrimitive(true), "skill-true", 1)
    }

    @Test
    fun `boolean false on agent_skills isEnabled validates and restores as 0`() = runBlocking {
        restoreAndAssert(JsonPrimitive(false), "skill-false", 0)
    }

    @Test
    fun `string in INTEGER column is rejected`() = runBlocking {
        val records = backup(skill(isEnabled = JsonPrimitive("true"), id = "skill-bad"))
        val failure = runCatching { repo.validate(records) }
        assertTrue(failure.isFailure)
        assertEquals("备份字段类型无效", failure.exceptionOrNull()?.message)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM agent_skills").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private suspend fun restoreAndAssert(isEnabled: JsonPrimitive, id: String, stored: Int) {
        val records = backup(skill(isEnabled = isEnabled, id = id))
        repo.validate(records)
        assertEquals(1, repo.merge(repo.snapshot(), records, "op-$id") {})
        db.openHelper.writableDatabase.query(
            "SELECT isEnabled FROM agent_skills WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse("isEnabled was NULL", cursor.isNull(0))
            assertEquals(stored, cursor.getInt(0))
        }
    }

    private fun backup(row: JsonObject): BackupRecords = BackupRecords(
        BackupRecordPolicy.tables.associateWith { table ->
            if (table == "agent_skills") listOf(row) else emptyList()
        },
    )

    private fun skill(isEnabled: JsonPrimitive, id: String) = buildJsonObject {
        put("id", id)
        put("name", "demo")
        put("description", "desc")
        put("systemPrompt", "prompt")
        put("triggerCommand", JsonNull)
        put("iconName", "icon")
        put("isEnabled", isEnabled)
        put("isBuiltin", JsonPrimitive(0))
        put("isImmutable", JsonPrimitive(0))
        put("category", "custom")
        put("resourcePath", JsonNull)
    }
}
