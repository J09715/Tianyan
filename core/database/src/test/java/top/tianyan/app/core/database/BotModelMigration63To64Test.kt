package top.tianyan.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotModelMigration63To64Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun `existing bots keep their profile and receive a nullable concrete model`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("bot-63-64.db").absolutePath
        helper.createDatabase(path, 63).use { db ->
            db.execSQL("""INSERT INTO persona_bots (id,name,role,systemPrompt,avatarSeed,modelId,sessionId,isMainBot,colorHex,createdAt,updatedAt)
                VALUES ('bot','Bot','coder','persona','seed','saved-profile','session',0,'#123456',1,1)""")
        }
        helper.runMigrationsAndValidate(path, 64, true, MIGRATION_63_64).use { db ->
            db.query("SELECT modelId,modelVariant FROM persona_bots WHERE id='bot'").use {
                assertTrue(it.moveToFirst())
                assertEquals("saved-profile", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
    }
}
