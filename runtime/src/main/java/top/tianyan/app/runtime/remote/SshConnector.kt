package top.tianyan.app.runtime.remote

import com.jcraft.jsch.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshAuthentication
import top.tianyan.app.core.model.SshCredentials
import java.security.MessageDigest
import java.util.Base64

class HostKeyConfirmationRequired(val fingerprint: String) : Exception("请核对服务器主机指纹")

class SshConnectionException(message: String) : Exception(message)

fun interface SshConnectionTester {
    suspend fun test(server: RemoteServer, credentials: SshCredentials)
}

class SshConnector(
    private val lanSockets: LanSocketOpener = LanSocketOpener(),
    private val userEnvironment: suspend () -> Map<String, String> = { emptyMap() },
) : SshConnectionTester {
    suspend fun openEnvironment(server: RemoteServer, credentials: SshCredentials, id: top.tianyan.app.runtime.environment.ExecutionEnvironmentId, logicalWorkspace: String = "/workspace"): top.tianyan.app.runtime.environment.ExecutionEnvironment {
        val session = connect(server, credentials)
        try {
            return SshExecutionEnvironment(server, session, id, credentials, logicalWorkspace, reconnect = ::connect, userEnvironment = userEnvironment)
        } catch (t: Throwable) { session.disconnect(); throw t }
    }
    internal suspend fun connect(server: RemoteServer, credentials: SshCredentials): Session = withContext(Dispatchers.IO) {
        server.validate()
        val client = JSch()
        var observed = ""
        client.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, key: ByteArray): Int {
                observed = fingerprint(key)
                return if (server.fingerprint == observed) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(key: HostKey, ui: UserInfo?) = Unit
            override fun remove(host: String, type: String?) = Unit
            override fun remove(host: String, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "tianyan-pinned-host"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        if (server.authentication == SshAuthentication.PRIVATE_KEY) {
            val key = credentials.secret.toByteArray()
            val phrase = credentials.passphrase.toByteArray()
            try { client.addIdentity(server.id, key, null, phrase.takeIf { it.isNotEmpty() }) }
            finally { key.fill(0); phrase.fill(0) }
        }
        val session = client.getSession(server.username, server.host, server.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (server.authentication == SshAuthentication.PASSWORD) "password" else "publickey")
        if (server.authentication == SshAuthentication.PASSWORD) session.setPassword(credentials.secret)
        session.timeout = 30_000
        session.serverAliveInterval = 15_000
        lanSockets.socketFactory(server.host, CONNECT_TIMEOUT_MS)?.let { session.setSocketFactory(it) }
        try {
            runInterruptible { session.connect(CONNECT_TIMEOUT_MS) }
            currentCoroutineContext().ensureActive()
            session
        } catch (t: Throwable) {
            session.disconnect()
            currentCoroutineContext().ensureActive()
            if (observed.isNotEmpty() && server.fingerprint.isBlank()) throw HostKeyConfirmationRequired(observed)
            if (observed.isNotEmpty() && server.fingerprint != observed) error("服务器主机指纹已变化，拒绝连接")
            throw t
        } finally { client.removeAllIdentity() }
    }

    override suspend fun test(server: RemoteServer, credentials: SshCredentials) {
        try {
            val session = connect(server, credentials)
            try {
                runInterruptible(Dispatchers.IO) {
                    val channel = session.openChannel("sftp") as ChannelSftp
                    try {
                        channel.connect(CONNECT_TIMEOUT_MS)
                        require(channel.stat(server.workspace).isDir) { "远程工作目录不存在或不是目录" }
                    } finally { channel.disconnect() }
                }
            } finally { session.disconnect() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyConfirmationRequired) {
            throw e
        } catch (e: Throwable) {
            throw sshTestFailure(e, server.host)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "命令参数包含非法字符" }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun remoteCommand(command: String, cwd: String, environment: Map<String, String>): String {
    val exports = environment.entries.joinToString(" ") { (key, value) ->
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "非法环境变量名称" }
        "export $key=${shellQuote(value)};"
    }
    return "cd -- ${shellQuote(cwd)} && { $exports $command\n}"
}
