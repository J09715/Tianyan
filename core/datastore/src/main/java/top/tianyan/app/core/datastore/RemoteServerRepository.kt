package top.tianyan.app.core.datastore

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.core.security.SecretManager
import java.io.File

interface RemoteServerRepository {
    val servers: StateFlow<List<RemoteServer>>
    val activeId: StateFlow<String?>
    val configurationError: StateFlow<String?> get() = MutableStateFlow(null)
    suspend fun credentials(id: String): SshCredentials
    suspend fun connection(id: String): Pair<RemoteServer, SshCredentials> =
        checkNotNull(servers.value.singleOrNull { it.id == id }) { "服务器配置已移除" } to credentials(id)
    suspend fun save(server: RemoteServer, credentials: SshCredentials)
    suspend fun select(id: String?)
    suspend fun remove(id: String)
}

/** Whole snapshot is encrypted, atomically replaced and excluded from Android backup. */
class EncryptedRemoteServerRepository internal constructor(private val file: AtomicFile,
    private val encrypt: (String) -> String, private val decrypt: (String) -> String?) : RemoteServerRepository {
    constructor(context: Context, secrets: SecretManager) : this(AtomicFile(File(context.noBackupFilesDir, "remote-servers.enc")), secrets::encrypt, secrets::decrypt)
    @Serializable private data class Entry(val server: RemoteServer, val credentials: SshCredentials)
    @Serializable private data class Snapshot(val entries: List<Entry> = emptyList(), val active: String? = null)
    private val mutex = Mutex()
    private val json = Json
    private val loaded = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) loadRemoteArchive(
        read = { file.openRead().use { require(it.available() <= 2 * 1024 * 1024); String(it.readBytes(), Charsets.UTF_8) } },
        decode = { encrypted -> json.decodeFromString(Snapshot.serializer(), checkNotNull(decrypt(encrypted))).also {
            require(it.entries.map { entry -> entry.server.id }.distinct().size == it.entries.size)
            it.entries.forEach { entry -> entry.server.validate(); require(entry.server.fingerprint.startsWith("SHA256:")) }
            require(it.active == null || it.entries.any { entry -> entry.server.id == it.active })
        } },
    ) else RemoteArchiveLoad(Snapshot(), null)
    private var snapshot: Snapshot = loaded.snapshot ?: Snapshot()
    private val errorState = MutableStateFlow(loaded.error)
    override val configurationError: StateFlow<String?> = errorState
    private val serverState = MutableStateFlow(snapshot.entries.map { it.server })
    private val activeState = MutableStateFlow(if (loaded.error != null) REMOTE_CONFIGURATION_UNAVAILABLE else snapshot.active)
    override val servers: StateFlow<List<RemoteServer>> = serverState
    override val activeId: StateFlow<String?> = activeState

    override suspend fun credentials(id: String) = mutex.withLock {
        snapshot.entries.single { it.server.id == id }.credentials
    }
    override suspend fun connection(id: String) = mutex.withLock {
        val entry = snapshot.entries.singleOrNull { it.server.id == id } ?: error("服务器配置已移除")
        entry.server to entry.credentials
    }
    override suspend fun save(server: RemoteServer, credentials: SshCredentials) = mutate {
        server.validate()
        require(server.fingerprint.startsWith("SHA256:")) { "请先验证服务器主机指纹" }
        require(credentials.secret.isNotBlank()) { "请输入密码或私钥" }
        copy(entries = entries.filterNot { it.server.id == server.id } + Entry(server, credentials))
    }
    override suspend fun select(id: String?) = mutate(recover = id == null) {
        require(id == null || entries.any { it.server.id == id }) { "服务器不存在" }
        copy(active = id)
    }
    override suspend fun remove(id: String) = mutate {
        copy(entries = entries.filterNot { it.server.id == id }, active = active.takeUnless { it == id })
    }
    private suspend fun mutate(recover: Boolean = false, update: Snapshot.() -> Snapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(errorState.value == null || recover) { "连接存档不可用，请先明确选择本地系统" }
            if (errorState.value != null) {
                // An explicit local selection unlocks recovery; preserve the damaged encrypted archive.
                if (file.baseFile.exists()) file.baseFile.copyTo(File(file.baseFile.path + ".unreadable-${System.nanoTime()}"))
            }
            val next = snapshot.update()
            val bytes = encrypt(json.encodeToString(Snapshot.serializer(), next)).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 2 * 1024 * 1024) { "连接存档超过大小上限" }
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (t: Throwable) { file.failWrite(stream); throw t }
            snapshot = next
            errorState.value = null
            serverState.value = next.entries.map { it.server }
            activeState.value = next.active
        }
    }
}
