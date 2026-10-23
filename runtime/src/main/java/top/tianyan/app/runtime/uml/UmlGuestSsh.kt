package top.tianyan.app.runtime.uml

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import top.tianyan.app.core.common.logging.AppLogger
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通过 SSH 与 UML guest 通信的访问层（移植自 Stryker GuestSsh/GuestExec，裁剪为最小可用面）。
 *
 * 机制：
 * - 应用生成一把 RSA 密钥对，公钥经 hostfs 共享目录（stryker.share=/host）注入 guest
 *   的 /root/.ssh/authorized_keys；
 * - guest 的 sshd 起来后，应用用私钥连 127.0.0.1:hostSshPort（passt hostfwd 到 guest:22）；
 * - exec 型命令走 ChannelExec，统一用一个带退出码哨兵的输出协议。
 *
 * 注意：UML guest 与宿主是同一台设备的同一内核，不走 9p/virtio，共享目录是 hostfs 直挂，
 * 所以公钥注入比 Stryker 更简单——文件落盘即生效。
 *
 * 身份：mwiede jsch 的 Session 没有 `setIdentityFile`，而是走 `IdentityRepository`。
 * 这里用一个最小的 `FileKeyRepository`，在每次连接时把 PEM 私钥解析给 jsch。
 */
@Singleton
class UmlGuestSsh @Inject constructor(
    private val logger: AppLogger,
) {
    private val jsch = JSch()

    private var shareDir: File? = null
    private var hostPort: Int = UmlRuntimeLayout.HOST_SSH_PORT
    private val sessionLock = Any()

    @Volatile
    private var session: Session? = null
    @Volatile
    private var lastLossAt: Long = 0L

    private val wantedForwards = ConcurrentHashMap<Int, Int>()

    val privateKey: File
        get() = File(keyDir(), "id_guest")
    val publicKey: File
        get() = File(keyDir(), "id_guest.pub")

    private fun keyDir(): File = File(shareDir?.absolutePath ?: "/data/data/top.tianyan.app/.keys", "tianyan-uml")

    /** 应用侧私钥存放目录（PEM 密钥对落盘处，keyDir + "-keys"）。 */
    private fun keyStoreDir(): File = File(shareDir?.absolutePath ?: "/data/data/top.tianyan.app/.keys", "tianyan-uml-keys")

    fun configure(context: Context, share: File, sshPort: Int) {
        synchronized(sessionLock) {
            this.shareDir = share
            this.hostPort = sshPort
        }
        ensureKeypair()
    }

    /** 生成并落盘应用侧的 RSA 密钥对（PEM 形式，JSch IdentityRepository 可解析）。 */
    private fun ensureKeypair() {
        val dir = keyStoreDir()
        dir.mkdirs()
        val priv = File(dir, "id_guest")
        val pub = File(dir, "id_guest.pub")
        if (priv.exists() && pub.exists()) return
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val privPem = "-----BEGIN PRIVATE KEY-----\n" +
            java.util.Base64.getEncoder().encodeToString((pair.private as java.security.PrivateKey).encoded) +
            "\n-----END PRIVATE KEY-----\n"
        File(dir, "id_guest").writeText(privPem)
        val pubKey = pair.public as java.security.interfaces.RSAPublicKey
        val blob = encodeOpenSshPublicKey(pubKey)
        File(dir, "id_guest.pub").writeText(
            "ssh-rsa " + java.util.Base64.getEncoder().encodeToString(blob) + " tianyan-uml\n",
        )
    }

    private fun encodeOpenSshPublicKey(key: java.security.interfaces.RSAPublicKey): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeString(data: ByteArray) {
            out.write(intToBytes(data.size))
            out.write(data)
        }
        fun writeMpi(value: BigInteger) {
            var raw = value.toByteArray()
            // BigInteger.toByteArray 带符号位，去掉前导 0x00
            if (raw[0] == 0x00.toByte() && raw.size > 1) raw = raw.copyOfRange(1, raw.size)
            writeString(raw)
        }
        writeString("ssh-rsa".toByteArray())
        writeMpi(key.publicExponent)
        writeMpi(key.modulus)
        return out.toByteArray()
    }

    /** 把应用公钥写进共享目录，guest 的 init 会挂到 /root/.ssh/authorized_keys。 */
    fun publishPublicKey() {
        val share = shareDir ?: throw IllegalStateException("no share directory configured")
        val sshDir = File(share, ".ssh")
        sshDir.mkdirs()
        val pubText = File(keyDir(), "id_guest.pub").readText()
        File(share, "authorized_keys").writeText(pubText)
    }

    fun ping(timeoutMs: Int): Boolean = runSentinel("echo __TING__\n") { it == "__TING__" }

    fun run(command: String): List<String> = openExec(command) ?: emptyList()

    /** 打开 guest 的交互式 shell channel（带 PTY，供终端会话用）。 */
    fun openShellChannel(columns: Int, rows: Int): ChannelShell {
        val s = synchronized(sessionLock) { ensureSession() }
            ?: throw IllegalStateException("no ssh session to the UML guest on :$hostPort")
        val channel = s.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color")
        channel.setPtySize(columns, rows, columns * 8, rows * 16)
        return channel
    }

    /** 打开 guest 的一次性 exec channel（无 PTY，供后台进程 / 协议会话用）。 */
    fun openExecChannel(command: String): ChannelExec {
        val s = synchronized(sessionLock) { ensureSession() }
            ?: throw IllegalStateException("no ssh session to the UML guest on :$hostPort")
        val channel = s.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        return channel
    }

    /** 执行命令，检查输出最后一行是否匹配（退出码哨兵协议）。 */
    fun runSentinel(command: String, check: (String) -> Boolean): Boolean {
        val lines = openExec(command) ?: return false
        val last = lines.lastOrNull()?.trim() ?: return false
        return check(last)
    }

    /** 执行一条命令并收集全部输出；guest 不可达时返回 null。 */
    private fun openExec(command: String): List<String>? {
        synchronized(sessionLock) {
            val s = ensureSession() ?: return null
            val channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val out = channel.inputStream
            channel.connect()
            // readBytes() 在通道被关/连接中断时会抛 IOException；调用方按「无输出」处理，
            // 不能让它冒泡出去。
            val bytes = try {
                out.readBytes()
            } catch (_: Exception) {
                ByteArray(0)
            } finally {
                runCatching { channel.disconnect() }
            }
            return String(bytes, Charsets.UTF_8).split('\n')
        }
    }

    private fun ensureSession(): Session? {
        session?.let { existing ->
            if (existing.isConnected) return existing
            try { existing.disconnect() } catch (_: Exception) {}
        }
        return try {
            // 身份：把应用侧 PEM 私钥 addIdentity 给 JSch 实例（公开 API），
            // 会话连接时自动用它做公钥认证——比 IdentityRepository 直接得多。
            val keyPath = File(keyStoreDir(), "id_guest")
            if (keyPath.isFile) jsch.addIdentity(keyPath.absolutePath)
            val s = jsch.getSession("root", UmlRuntimeLayout.HOST_LOOPBACK, hostPort)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("UserKnownHostsFile", "/dev/null")
            s.setTimeout(20_000)
            s.connect()
            session = s
            s
        } catch (e: Exception) {
            logger.w("UML guest ssh connect failed", e)
            lastLossAt = System.currentTimeMillis()
            session = null
            null
        }
    }

    /** 本地端口转发（宿主 port → guest port），用于把 guest 内服务映射到 127.0.0.1。 */
    fun forwardLocalPort(hostPort: Int, guestPort: Int): Boolean {
        val s = synchronized(sessionLock) { ensureSession() } ?: return false
        return try {
            s.setPortForwardingL(hostPort, UmlRuntimeLayout.HOST_LOOPBACK, guestPort)
            wantedForwards[hostPort] = guestPort
            true
        } catch (e: Exception) {
            logger.w("port forward $hostPort->$guestPort failed", e)
            false
        }
    }

    fun unforwardLocalPort(port: Int) {
        synchronized(sessionLock) {
            session?.let {
                try { it.delPortForwardingL(port) } catch (_: Exception) {}
            }
            wantedForwards.remove(port)
        }
    }

    fun disconnect() {
        synchronized(sessionLock) {
            session?.let {
                try { it.disconnect() } catch (_: Exception) {}
            }
            session = null
        }
    }

    fun lastLossAt(): Long = lastLossAt

    companion object {
        private fun intToBytes(value: Int): ByteArray =
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
    }
}
