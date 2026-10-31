package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptCachingMigration51To52Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath = InstrumentationRegistry.getInstrumentation()
        .targetContext.getDatabasePath("prompt-caching-migration").absolutePath

    @Test
    fun `migration adds prompt caching columns with safe defaults`() {
        helper.createDatabase(dbPath, 51).close()

        helper.runMigrationsAndValidate(dbPath, 52, true, MIGRATION_51_52).use { db ->
            val defaults = buildMap {
                db.query("PRAGMA table_info(`harness_models`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        put(name, cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                    }
                }
            }
            // 默认开启缓存、默认 5 分钟 TTL
            assertEquals("1", defaults["promptCachingEnabled"])
            assertEquals("0", defaults["promptCacheTtl1h"])
        }
    }
}