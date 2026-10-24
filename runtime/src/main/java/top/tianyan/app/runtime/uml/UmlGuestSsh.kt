package top.tianyan.app.runtime.uml

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import top.tianyan.app.core.common.logging.AppLogger
import java.io.File
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

    /**
     * 应用侧密钥对落盘目录。
     *
     * 生成（[ensureKeypair]）、发布（[publishPublicKey]）、连接（[ensureSession]）
     * 三处必须用同一处，否则会读不到刚生成的密钥。
     */
    private fun keyStoreDir(): File = File(shareDir?.absolutePath ?: "/data/data/top.tianyan.app/.keys", "tianyan-uml-keys")

    fun configure(context: Context, share: File, sshPort: Int) {
        synchronized(sessionLock) {
            this.shareDir = share
            this.hostPort = sshPort
        }
        ensureKeypair()
    }

    /**
     * 生成并落盘应用侧密钥对。
     *
     * 用 **JSch 自己生成并写出** RSA 密钥对。
     *
     * 为什么不用手写编码 / 不用 ed25519：
     * - ed25519：JSch 0.2.21 的 `KeyPairEdDSA.getPrivateKey()` 抛
     *   `UnsupportedOperationException`，私钥根本写不出来（实测），
     *   异常被吞掉后静默 fallback，等于改动从未生效。
     * - 手写 RSA 公钥：`BigInteger.toByteArray()` **已经**正确处理符号位
     *   （最高位为 1 时带 0x00 前缀），手写时若再删掉这个前缀，mpint 就坏了 ——
     *   sshd 报 `userauth_pubkey: parse key: invalid format`，
     *   ssh-keygen 也报 "is not a public key file"。
     *
     * 交给 JSch：它写的 PKCS1 私钥 `addIdentity` 读得动，写的公钥格式标准。
     * ssh-rsa 公钥类型在 OpenSSH 9.2 上仍可用（映射到 rsa-sha2-256/512 签名）。
     */
    private fun ensureKeypair() {
        val dir = keyStoreDir()
        dir.mkdirs()
        val priv = File(dir, "id_guest")
        val pub = File(dir, "id_guest.pub")
        if (priv.exists() && pub.exists() && pub.readText().startsWith("ssh-")) return
        runCatching {
            val jsch = com.jcraft.jsch.JSch()
            val pair = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.RSA)
            pair.writePrivateKey(priv.absolutePath)
            pair.writePublicKey(pub.absolutePath, "tianyan-uml")
            pair.dispose()
            logger.i("generated RSA keypair for UML guest (jsch-native format)")
        }.onFailure {
            logger.w("keypair generation failed: ${it.message}", it)
        }
    }


    /** 把应用公钥写进共享目录，guest 的 init 会挂到 /root/.ssh/authorized_keys。 */
    fun publishPublicKey() {
        val share = shareDir ?: throw IllegalStateException("no share directory configured")
        val sshDir = File(share, ".ssh")
        sshDir.mkdirs()
        // 必须与 ensureKeypair 的落盘位置一致（keyStoreDir）：
        // 早先读的是 keyDir（tianyan-uml），而密钥写在 tianyan-uml-keys，
        // 于是发布到 guest 的是旧文件内容或干脆读不到 —— 认证必然失败。
        val pubFile = File(keyStoreDir(), "id_guest.pub")
        val pubText = pubFile.takeIf { it.isFile }?.readText()
            ?: throw IllegalStateException(
                "guest public key missing at ${pubFile.absolutePath} — cannot authenticate",
            )
        // 必须写进 .ssh/ 子目录：guest 侧的 stryker-guest-init 读的是
        // `$SHARE/.ssh/authorized_keys`（KEYDIR=$SHARE/.ssh），写到 share 根目录
        // 它看不到 —— 表现就是 sshd 起来了但认证失败，应用一直 ping 不通。
        File(sshDir, "authorized_keys").writeText(pubText)
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
}
