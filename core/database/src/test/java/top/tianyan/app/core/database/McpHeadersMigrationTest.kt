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
class McpHeadersMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun `upgrade preserves existing servers without credentials`() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("mcp-headers-migration").absolutePath
        helper.createDatabase(path, 73).use { db ->
            db.execSQL("""INSERT INTO mcp_servers
                (id,name,description,transportType,command,argsCiphertext,envCiphertext,serverUrl,isEnabled,isBuiltin,userToggled,
                authMode,oauthClientId,oauthAuthorizationEndpoint,oauthTokenEndpoint,oauthRedirectUri,oauthScope,oauthResource)
                VALUES ('remote','Remote','','SSE','','','','https://example.test/mcp',1,0,0,'NONE','','','','tianyan://oauth/mcp','','')""")
        }
        helper.runMigrationsAndValidate(path, 74, true, MIGRATION_73_74).use { db ->
            db.query("SELECT headersCiphertext, serverUrl FROM mcp_servers WHERE id='remote'").use {
                assertTrue(it.moveToFirst())
                assertEquals("", it.getString(0))
                assertEquals("https://example.test/mcp", it.getString(1))
            }
        }
    }
}
