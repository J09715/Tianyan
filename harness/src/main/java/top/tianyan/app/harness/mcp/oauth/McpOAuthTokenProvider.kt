package top.tianyan.app.harness.mcp.oauth

import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.database.McpOAuthCredentialRepository
import top.tianyan.app.core.model.McpServerConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import top.tianyan.app.core.model.McpAuthMode

/** Read-only token provider used by MCP transport; refresh orchestration is added by coordinator. */
class McpOAuthTokenProvider(
    private val credentials: McpOAuthCredentialRepository,
    private val coordinator: McpOAuthCoordinator,
) {
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun forceRefresh(serverId: String): String? = coordinator.refresh(serverId, force = true)

    suspend fun accessToken(server: McpServerConfig): String? {
        if (server.authMode != McpAuthMode.OAUTH) return null
        val credential = credentials.credential(server.id) ?: return null
        val expiresAt = credential.expiresAt
        if (expiresAt == null || expiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) {
            return credential.accessToken
        }
        return refreshLocks.getOrPut(server.id) { Mutex() }.withLock {
            val latest = credentials.credential(server.id) ?: return@withLock null
            val latestExpiresAt = latest.expiresAt
            if (latestExpiresAt == null || latestExpiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) {
                latest.accessToken
            } else {
                coordinator.refresh(server.id)
            }
        }
    }

    companion object {
        private const val EXPIRY_SKEW_MS = 30_000L
    }
}
