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

/** MIGRATION_79_80：新增 btw_queries 旁路问答记录表（纯新增，无回填）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BtwQueryMigration79To80Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // Room 2.8 默认 JVM driver 与 Robolectric 的 databasePath 解析不一致，须显式 framework factory
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB).absolutePath

    @Test
    fun `creates btw_queries table and accepts rows`() {
        helper.createDatabase(dbPath, 79).close()

        helper.runMigrationsAndValidate(dbPath, 80, true, MIGRATION_79_80).use { db ->
            db.execSQL(
                """INSERT INTO btw_queries (id, sessionId, question, answer, status, error, createdAt, finishedAt,
                   modelId, providerId, inputTokens, outputTokens, cachedTokens)
                   VALUES ('btw_1', 'sess_1', '现在做到哪一步了？', '正在跑测试', 'COMPLETED', '', 100, 200,
                   'deepseek-chat', 'deepseek', 1200, 80, 900)""",
            )
            db.query("SELECT question, status, inputTokens FROM btw_queries WHERE id = 'btw_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("现在做到哪一步了？", cursor.getString(0))
                assertEquals("COMPLETED", cursor.getString(1))
                assertEquals(1200L, cursor.getLong(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "btw-migration-test.db"
    }
}
