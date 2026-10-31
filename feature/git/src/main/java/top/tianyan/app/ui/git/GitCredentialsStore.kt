package top.tianyan.app.ui.git

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.tianyan.app.core.security.SecretManager

/**
 * Git 远程凭据存储（HTTPS + Token）：
 * - 按 host（如 github.com）一组 用户名 + Token；
 * - Token 经 SecretManager（AndroidKeyStore AES/GCM）加密后落盘 JSON，绝不明文存储；
 * - 文件为 App 私有目录，仅本应用可读。
 */
class GitCredentialsStore(
    private val context: Context,
    private val secretManager: SecretManager,
) {
    @Serializable
    data class StoredCredentials(val username: String, val tokenEncrypted: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private fun file(): File = File(context.filesDir, "git_credentials.json")

    private fun readMap(): MutableMap<String, StoredCredentials> = runCatching {
        if (!file().isFile) return mutableMapOf()
        json.decodeFromString<Map<String, StoredCredentials>>(file().readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeMap(map: Map<String, StoredCredentials>) {
        file().writeText(json.encodeToString(map))
    }

    /** 返回 (username, token)；未配置返回 null。 */
    suspend fun get(host: String): Pair<String, String>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stored = readMap()[host] ?: return@withContext null
            val token = secretManager.decrypt(stored.tokenEncrypted) ?: return@withContext null
            stored.username to token
        }
    }

    suspend fun save(host: String, username: String, token: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = readMap()
            map[host] = StoredCredentials(username.trim(), secretManager.encrypt(token))
            writeMap(map)
        }
    }

    suspend fun remove(host: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = readMap()
            map.remove(host)
            writeMap(map)
        }
    }

    suspend fun listHosts(): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) { readMap().keys.sorted() }
    }

    companion object {
        /** 从远程 URL 提取 host：https://github.com/x/y.git → github.com；git@github.com:x/y.git → github.com */
        fun extractHost(remoteUrl: String): String {
            val url = remoteUrl.trim()
            return when {
                url.startsWith("http://", true) || url.startsWith("https://", true) ->
                    url.substringAfter("//").substringBefore('/').substringBefore(':').substringBefore('@')
                url.startsWith("git@") ->
                    url.substringAfter("git@").substringBefore(':').substringBefore('/')
                url.startsWith("ssh://") ->
                    url.substringAfter("//").substringBefore('/').substringBefore(':').substringBefore('@')
                else -> url.substringBefore('/').substringBefore(':')
            }.lowercase().ifBlank { url }
        }
    }
}
