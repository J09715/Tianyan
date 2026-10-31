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
class McpOAuthMigration49To50Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbPath = InstrumentationRegistry.getInstrumentation()
        .targetContext.getDatabasePath("mcp-oauth-migration").absolutePath

    @Test
    fun `migration adds oauth config and encrypted credential tables`() {
        helper.createDatabase(dbPath, 49).use { db ->
            db.execSQL(
                """INSERT INTO mcp_servers
                    (id,name,description,transportType,command,argsCiphertext,envCiphertext,serverUrl,isEnabled,isBuiltin,userToggled)
                    VALUES ('remote','Remote','','SSE','','','','https://example.test/mcp',1,0,0)""",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 50, true, MIGRATION_49_50).use { db ->
            db.query("SELECT authMode, oauthRedirectUri FROM mcp_servers WHERE id='remote'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("NONE", cursor.getString(0))
                assertEquals("tianyan://oauth/mcp", cursor.getString(1))
            }
            db.execSQL(
                """INSERT INTO mcp_oauth_credentials
                    (serverId,accessTokenCiphertext,refreshTokenCiphertext,tokenType,scope,expiresAt,authorizationServer,tokenEndpoint,clientId,resource,credentialRevision,updatedAt)
                    VALUES ('remote','encrypted-access','encrypted-refresh','Bearer',NULL,NULL,NULL,'https://auth.test/token','client',NULL,1,1)""",
            )
            db.execSQL(
                """INSERT INTO mcp_oauth_transactions
                    (state,serverId,codeVerifierCiphertext,redirectUri,clientId,authorizationEndpoint,tokenEndpoint,resource,createdAt,expiresAt)
                    VALUES ('state','remote','encrypted-verifier','tianyan://oauth/mcp','client','https://auth.test/authorize','https://auth.test/token',NULL,1,2)""",
            )
            db.query("SELECT COUNT(*) FROM mcp_oauth_credentials").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
