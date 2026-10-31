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
class BotModelPolicyMigration72To73Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `upgrade locks existing selections without changing their model or variant`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("bot-model-policy-72-73.db").absolutePath
        helper.createDatabase(path, 72).use { db ->
            db.execSQL("""INSERT INTO persona_bots
                (id,name,role,systemPrompt,avatarSeed,modelId,providerId,sessionId,isMainBot,colorHex,createdAt,updatedAt,modelVariant)
                VALUES ('bot','Bot','role','persona','seed','profile',NULL,'dm',0,'#000000',1,1,'variant')""")
        }
        helper.runMigrationsAndValidate(path, 73, true, MIGRATION_72_73).use { db ->
            db.query("SELECT modelId,modelVariant,allowModelSelection FROM persona_bots WHERE id = 'bot'").use {
                assertTrue(it.moveToFirst())
                assertEquals("profile", it.getString(0))
                assertEquals("variant", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
            db.execSQL("UPDATE persona_bots SET allowModelSelection = 1 WHERE id = 'bot'")
            db.query("SELECT allowModelSelection FROM persona_bots WHERE id = 'bot'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }
}
