package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.security.SecretManager

/** OAuth metadata and encrypted token material for one HTTP MCP server. */
@Entity(tableName = "mcp_oauth_credentials")
data class McpOAuthCredentialEntity(
    @PrimaryKey val serverId: String,
    val accessTokenCiphertext: String,
    val refreshTokenCiphertext: String,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val expiresAt: Long? = null,
    val authorizationServer: String? = null,
    val tokenEndpoint: String? = null,
    val clientId: String,
    val resource: String? = null,
    /** Non-secret revision. Include this in transport/session fingerprints. */
    val credentialRevision: Long,
    val updatedAt: Long,
)

/** Short-lived browser authorization transaction. Secret fields are encrypted. */
@Entity(
    tableName = "mcp_oauth_transactions",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["expiresAt"]),
    ],
)
data class McpOAuthTransactionEntity(
    @PrimaryKey val state: String,
    val serverId: String,
    val codeVerifierCiphertext: String,
    val redirectUri: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String? = null,
    val createdAt: Long,
    val expiresAt: Long,
)

@Dao
interface McpOAuthCredentialDao {
    @Query("SELECT * FROM mcp_oauth_credentials WHERE serverId = :serverId LIMIT 1")
    suspend fun find(serverId: String): McpOAuthCredentialEntity?

    @Query("SELECT * FROM mcp_oauth_credentials")
    fun observeAll(): Flow<List<McpOAuthCredentialEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: McpOAuthCredentialEntity)

    @Query("DELETE FROM mcp_oauth_credentials WHERE serverId = :serverId")
    suspend fun delete(serverId: String)
}

@Dao
interface McpOAuthTransactionDao {
    @Query("SELECT * FROM mcp_oauth_transactions WHERE state = :state LIMIT 1")
    suspend fun find(state: String): McpOAuthTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: McpOAuthTransactionEntity)

    @Query("DELETE FROM mcp_oauth_transactions WHERE state = :state")
    suspend fun delete(state: String)

    /** Atomically claims a still-live transaction after callback validation. */
    @Query("DELETE FROM mcp_oauth_transactions WHERE state = :state AND expiresAt > :now")
    suspend fun claim(state: String, now: Long): Int

    @Query("DELETE FROM mcp_oauth_transactions WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("DELETE FROM mcp_oauth_transactions WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)
}

/** Room facade that keeps token plaintext out of entities and UI flows. */
class McpOAuthCredentialRepository(
    private val credentials: McpOAuthCredentialDao,
    private val transactions: McpOAuthTransactionDao,
    private val secretManager: SecretManager,
) {
    fun authorizedServerIds(): Flow<Set<String>> = credentials.observeAll().map { rows ->
        rows.filter { row ->
            secretManager.decrypt(row.accessTokenCiphertext)?.isNotBlank() == true
        }.mapTo(linkedSetOf()) { it.serverId }
    }
    suspend fun credential(serverId: String): McpOAuthCredential? = credentials.find(serverId)?.let { row ->
        McpOAuthCredential(
            serverId = row.serverId,
            accessToken = secretManager.decrypt(row.accessTokenCiphertext),
            refreshToken = secretManager.decrypt(row.refreshTokenCiphertext),
            tokenType = row.tokenType,
            scope = row.scope,
            expiresAt = row.expiresAt,
            authorizationServer = row.authorizationServer,
            tokenEndpoint = row.tokenEndpoint,
            clientId = row.clientId,
            resource = row.resource,
            credentialRevision = row.credentialRevision,
        )
    }


    suspend fun saveCredential(value: McpOAuthCredential) {
        credentials.upsert(
            McpOAuthCredentialEntity(
                serverId = value.serverId,
                accessTokenCiphertext = secretManager.encrypt(value.accessToken.orEmpty()),
                refreshTokenCiphertext = secretManager.encrypt(value.refreshToken.orEmpty()),
                tokenType = value.tokenType,
                scope = value.scope,
                expiresAt = value.expiresAt,
                authorizationServer = value.authorizationServer,
                tokenEndpoint = value.tokenEndpoint,
                clientId = value.clientId,
                resource = value.resource,
                credentialRevision = value.credentialRevision,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteCredential(serverId: String) = credentials.delete(serverId)

    suspend fun saveTransaction(value: McpOAuthTransaction) {
        transactions.upsert(
            McpOAuthTransactionEntity(
                state = value.state,
                serverId = value.serverId,
                codeVerifierCiphertext = secretManager.encrypt(value.codeVerifier),
                redirectUri = value.redirectUri,
                clientId = value.clientId,
                authorizationEndpoint = value.authorizationEndpoint,
                tokenEndpoint = value.tokenEndpoint,
                resource = value.resource,
                createdAt = value.createdAt,
                expiresAt = value.expiresAt,
            ),
        )
    }

    suspend fun transaction(state: String, now: Long = System.currentTimeMillis()): McpOAuthTransaction? {
        transactions.deleteExpired(now)
        val row = transactions.find(state) ?: return null
        if (row.expiresAt <= now) return null
        return McpOAuthTransaction(
            state = row.state,
            serverId = row.serverId,
            codeVerifier = secretManager.decrypt(row.codeVerifierCiphertext) ?: return null,
            redirectUri = row.redirectUri,
            clientId = row.clientId,
            authorizationEndpoint = row.authorizationEndpoint,
            tokenEndpoint = row.tokenEndpoint,
            resource = row.resource,
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
        )
    }

    suspend fun claimTransaction(state: String, now: Long = System.currentTimeMillis()): Boolean =
        transactions.claim(state, now) == 1

    suspend fun deleteForServer(serverId: String) {
        transactions.deleteForServer(serverId)
        credentials.delete(serverId)
    }
}

data class McpOAuthCredential(
    val serverId: String,
    val accessToken: String?,
    val refreshToken: String?,
    val tokenType: String,
    val scope: String?,
    val expiresAt: Long?,
    val authorizationServer: String?,
    val tokenEndpoint: String?,
    val clientId: String,
    val resource: String?,
    val credentialRevision: Long,
)

data class McpOAuthTransaction(
    val state: String,
    val serverId: String,
    val codeVerifier: String,
    val redirectUri: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String?,
    val createdAt: Long,
    val expiresAt: Long,
)
