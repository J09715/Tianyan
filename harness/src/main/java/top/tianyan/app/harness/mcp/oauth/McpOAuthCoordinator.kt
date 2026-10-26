package top.tianyan.app.harness.mcp.oauth

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tianyan.app.core.database.McpOAuthCredential
import top.tianyan.app.core.database.McpOAuthCredentialRepository
import top.tianyan.app.core.database.McpOAuthTransaction
import top.tianyan.app.core.model.McpServerConfig
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import top.tianyan.app.core.model.McpAuthMode
import top.tianyan.app.core.model.McpAuthState

/**
 * Authorization Code + PKCE coordinator. Browser/UI integration calls begin() and callback().
 * The coordinator never returns token material to callers; only authorization URL/state/result.
 */
class McpOAuthCoordinator(
    private val credentials: McpOAuthCredentialRepository,
    private val client: OkHttpClient,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, McpAuthState>>(emptyMap())
    val states: StateFlow<Map<String, McpAuthState>> = _states

    suspend fun begin(server: McpServerConfig, now: Long = System.currentTimeMillis()): String {
        require(server.authMode == McpAuthMode.OAUTH) { "MCP 服务未配置 OAuth" }
        require(server.oauthClientId.isNotBlank()) { "OAuth client_id 未配置" }
        require(server.oauthAuthorizationEndpoint.isNotBlank()) { "OAuth authorization endpoint 未配置" }
        require(server.oauthTokenEndpoint.isNotBlank()) { "OAuth token endpoint 未配置" }
        OAuthEndpointPolicy.requireSecure(server.oauthAuthorizationEndpoint)
        OAuthEndpointPolicy.requireSecure(server.oauthTokenEndpoint)
        _states.value = _states.value + (server.id to McpAuthState.Authorizing)
        val state = OAuthPkce.randomState()
        val verifier = OAuthPkce.codeVerifier()
        credentials.saveTransaction(
            McpOAuthTransaction(
                state = state,
                serverId = server.id,
                codeVerifier = verifier,
                redirectUri = server.oauthRedirectUri,
                clientId = server.oauthClientId,
                authorizationEndpoint = server.oauthAuthorizationEndpoint,
                tokenEndpoint = server.oauthTokenEndpoint,
                resource = server.oauthResource.takeIf { it.isNotBlank() },
                createdAt = now,
                expiresAt = now + TRANSACTION_TTL_MS,
            ),
        )
        return Uri.parse(server.oauthAuthorizationEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", server.oauthClientId)
            .appendQueryParameter("redirect_uri", server.oauthRedirectUri)
            .appendQueryParameter("code_challenge", OAuthPkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply { server.oauthScope.takeIf { it.isNotBlank() }?.let { appendQueryParameter("scope", it) } }
            .apply { server.oauthResource.takeIf { it.isNotBlank() }?.let { appendQueryParameter("resource", it) } }
            .build()
            .toString()
    }

    suspend fun callback(uri: Uri, now: Long = System.currentTimeMillis()): CallbackResult {
        val state = uri.oauthState() ?: return CallbackResult.Invalid("OAuth 回调缺少 state")
        val transaction = credentials.transaction(state, now)
            ?: return CallbackResult.Invalid("OAuth state 无效、已过期或已被消费")
        val redirect = Uri.parse(transaction.redirectUri)
        if (uri.scheme != redirect.scheme || uri.host != redirect.host || uri.path != redirect.path) {
            return CallbackResult.Invalid("OAuth 回调地址不匹配")
        }
        val error = uri.oauthError()
        val code = uri.oauthCode()
        if (error == null && code.isNullOrBlank()) return CallbackResult.Invalid("OAuth 回调缺少授权 code")
        // URI/state/code/error 均验证完毕后才原子消费，畸形回调不能烧掉真实事务。
        if (!credentials.claimTransaction(state, now)) {
            return CallbackResult.Invalid("OAuth state 已被消费")
        }
        if (error != null) {
            _states.value = _states.value + (transaction.serverId to McpAuthState.Error(error))
            return CallbackResult.Cancelled(error)
        }
        val token = try {
            exchange(transaction, code!!)
        } catch (cancellation: CancellationException) {
            _states.value = _states.value - transaction.serverId
            throw cancellation
        } catch (failure: Throwable) {
            _states.value = _states.value + (
                transaction.serverId to McpAuthState.Error(
                    failure.message ?: "OAuth token exchange failed",
                )
            )
            throw failure
        }
        val lock = locks.getOrPut(transaction.serverId) { Mutex() }
        lock.withLock {
            credentials.saveCredential(
                McpOAuthCredential(
                    serverId = transaction.serverId,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    tokenType = token.tokenType,
                    scope = token.scope,
                    expiresAt = token.expiresAt,
                    authorizationServer = null,
                    tokenEndpoint = transaction.tokenEndpoint,
                    clientId = transaction.clientId,
                    resource = transaction.resource,
                    credentialRevision = now,
                ),
            )
        }
        _states.value = _states.value - transaction.serverId
        return CallbackResult.Authorized(transaction.serverId)
    }

    suspend fun refresh(
        serverId: String,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): String? {
        val lock = locks.getOrPut(serverId) { Mutex() }
        return lock.withLock {
            val current = credentials.credential(serverId) ?: return@withLock null
            val expiresAt = current.expiresAt
            if (!force && (expiresAt == null || expiresAt > now + REFRESH_SKEW_MS)) {
                return@withLock current.accessToken
            }
            val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() } ?: return@withLock null
            val endpoint = current.tokenEndpoint?.takeIf { it.isNotBlank() } ?: return@withLock null
            OAuthEndpointPolicy.requireSecure(endpoint)
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", current.clientId)
                .apply { current.resource?.let { add("resource", it) } }
                .build()
            val request = Request.Builder().url(endpoint).post(body).build()
            _states.value = _states.value + (serverId to McpAuthState.Authorizing)
            try {
                val token = executeTokenRequest(request, now)
                credentials.saveCredential(
                    current.copy(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken ?: refreshToken,
                        tokenType = token.tokenType,
                        scope = token.scope ?: current.scope,
                        expiresAt = token.expiresAt,
                        credentialRevision = now,
                    ),
                )
                _states.value = _states.value - serverId
                token.accessToken
            } catch (cancellation: CancellationException) {
                _states.value = _states.value - serverId
                throw cancellation
            } catch (failure: Throwable) {
                _states.value = _states.value + (serverId to McpAuthState.Error(failure.message ?: "OAuth token refresh failed"))
                throw failure
            }
        }
    }

    suspend fun logout(serverId: String) {
        credentials.deleteCredential(serverId)
        locks.remove(serverId)
        _states.value = _states.value - serverId
    }

    private suspend fun exchange(transaction: McpOAuthTransaction, code: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", transaction.redirectUri)
            .add("client_id", transaction.clientId)
            .add("code_verifier", transaction.codeVerifier)
            .apply { transaction.resource?.let { add("resource", it) } }
            .build()
        OAuthEndpointPolicy.requireSecure(transaction.tokenEndpoint)
        val request = Request.Builder().url(transaction.tokenEndpoint).post(body).build()
        return executeTokenRequest(request, System.currentTimeMillis())
    }

    private suspend fun executeTokenRequest(request: Request, now: Long): TokenResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use {
                            val text = it.body.string()
                            check(it.isSuccessful) { "OAuth token request failed (HTTP ${it.code})" }
                            val json = JSONObject(text)
                            val access = json.optString("access_token").takeIf { value -> value.isNotBlank() }
                                ?: error("OAuth token response missing access_token")
                            val expires = json.optLong("expires_in", Long.MIN_VALUE).takeIf { value -> value != Long.MIN_VALUE }
                                ?.let { value -> now + value * 1000L }
                            continuation.resume(TokenResponse(
                                accessToken = access,
                                refreshToken = json.optString("refresh_token").takeIf { value -> value.isNotBlank() },
                                tokenType = json.optString("token_type", "Bearer"),
                                scope = json.optString("scope").takeIf { value -> value.isNotBlank() },
                                expiresAt = expires,
                            ))
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val tokenType: String,
        val scope: String?,
        val expiresAt: Long?,
    )

    sealed interface CallbackResult {
        data class Authorized(val serverId: String) : CallbackResult
        data class Invalid(val message: String) : CallbackResult
        data class Cancelled(val reason: String) : CallbackResult
    }

    companion object {
        const val TRANSACTION_TTL_MS = 10 * 60 * 1000L
        const val REFRESH_SKEW_MS = 30_000L
    }
}
