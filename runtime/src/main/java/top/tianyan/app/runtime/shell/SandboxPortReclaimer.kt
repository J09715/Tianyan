package top.tianyan.app.runtime.shell

import android.os.Process as AndroidProcess
import java.io.File
import java.nio.file.Files

/**
 * 沙箱残留监听者回收。
 *
 * 网关真正的 LISTEN 者往往不是 PRoot tracer 本身，而是它的孙进程（`sh` 下的 host/server）。tracer 被
 * SIGKILL 或提前退出时来不及带走它们，它们就被 reparent 到 init：**端口还占着，本进程的进程表里却
 * 查无此人**。此后停止看起来成功了，下一次启动却永远面对「服务端口已被占用，拒绝连接未知进程」。
 *
 * 判定分两步，先精确后兜底：
 * 1. 精确：从 `/proc/net/tcp{,6}` 找出 [port] 上 LISTEN 的 socket inode，再经 `/proc/<pid>/fd`
 *    反查到持有点（只有同 uid 的 fd 可读，他应用/系统监听者天然落选）；
 * 2. 兜底：网络表读不到时（部分内核/SELinux 会屏蔽 `/proc/net`）退回「本运行时的全部进程」——
 *    这一步只在调用方明确要求腾出端口时触发，且**所有 App 已知的会话根都被豁免**。
 *
 * 归属（是不是我们的沙箱进程）用 `exe` / `cwd` / `cmdline` 任一落在本运行时目录内判定：二进制被
 * 替换成 `(deleted)`、或 exe 不可读时仍有 cmdline（tracer 命令行必然带 rootfs/opt 路径）兜底。
 *
 * 沿父链向上走到「第一个不属于本运行时的父进程」为止，取最高的本运行时祖先作为这棵树的根。
 * 注意：**不能因为根挂在宿主 App 下就认为它「仍有主人」** —— 托管网关的 tracer 恰恰是 App 的直接
 * 子进程，停止失败后它在登记表之外存活，越过它就会把端口永久占住。真正的主人只由调用方显式传入的
 * [protectedRoots]（进程登记表 + 终端会话 + 前台命令）认定。
 */
class SandboxPortReclaimer(
    private val ownedRoots: List<File>,
    private val proc: SandboxProcFS = LinuxSandboxProcFS,
    private val signal: (Long) -> Boolean = ::killOwnedProcess,
) {
    constructor(ownedRoot: File) : this(listOf(ownedRoot))

    private val ownedPrefixes: List<String> by lazy {
        ownedRoots.mapNotNull { root -> runCatching { root.canonicalFile.absolutePath }.getOrNull() }
            .map { if (it.endsWith(File.separator)) it else it + File.separator }
    }

    /**
     * 回收 [port] 上被遗弃的本运行时监听者及其整棵子树，返回成功收到信号的进程数。
     *
     * [protectedRoots] 是调用方仍在册/仍在用的会话根 pid（登记表进程、终端会话、前台命令）；
     * 命中即整棵树放过。
     */
    fun reclaim(port: Int, protectedRoots: Set<Long> = emptySet()): Int {
        val table = proc.entries()
        if (table.isEmpty() || ownedPrefixes.isEmpty()) return 0
        val abandoned = abandonedRoots(port, table, protectedRoots)
        if (abandoned.isEmpty()) return 0
        val childrenOf = childrenByParent(table)
        return abandoned.flatMap { root -> listOf(root) + descendantsOf(childrenOf, root) }
            .distinct()
            .count { signal(it) }
    }

    private fun abandonedRoots(
        port: Int,
        table: Map<Long, SandboxProcEntry>,
        protectedRoots: Set<Long>,
    ): List<Long> {
        val inodes = proc.listeningSocketInodes(port)
        val owned = table.keys.filter { pid -> isOwned(table[pid]) }
        val listeners = if (inodes.isEmpty()) owned else owned.filter { pid -> proc.socketInodes(pid).any(inodes::contains) }
        if (listeners.isEmpty()) return emptyList()
        return listeners.map { abandonedRoot(it, table) }.filter { it !in protectedRoots }.distinct()
    }

    /** 从 [listener] 上行到最高的本运行时祖先；父进程不属于本运行时（宿主 App / init / 已消失）即停。 */
    private fun abandonedRoot(listener: Long, table: Map<Long, SandboxProcEntry>): Long {
        var current = listener
        while (true) {
            val parent = table[current]?.parent
            if (parent == null || parent <= INIT_PID || parent !in table || !isOwned(table[parent])) return current
            current = parent
        }
    }

    private fun childrenByParent(table: Map<Long, SandboxProcEntry>): Map<Long, List<Long>> = table.entries
        .filter { it.value.parent in table.keys }
        .groupBy({ checkNotNull(it.value.parent) }, { it.key })

    private fun descendantsOf(childrenOf: Map<Long, List<Long>>, rootPid: Long): List<Long> {
        val found = mutableListOf<Long>()
        val pending = ArrayDeque(childrenOf[rootPid].orEmpty())
        while (pending.isNotEmpty()) {
            val pid = pending.removeFirst()
            found += pid
            pending += childrenOf[pid].orEmpty()
        }
        return found
    }

    private fun isOwned(entry: SandboxProcEntry?): Boolean {
        if (entry == null) return false
        val paths = listOfNotNull(entry.executable, entry.workingDirectory).map { it.removeSuffix(DELETED_SUFFIX) }
        if (paths.any { path -> ownedPrefixes.any(path::startsWith) }) return true
        val commandLine = entry.commandLine ?: return false
        return ownedPrefixes.any(commandLine::contains)
    }

    private companion object {
        const val INIT_PID = 1L
        const val DELETED_SUFFIX = " (deleted)"
    }
}

/** pid 归属信息；抽出来是为了让单元测试不必真的读 procfs。 */
data class SandboxProcEntry(
    val parent: Long?,
    val executable: String?,
    val workingDirectory: String? = null,
    val commandLine: String? = null,
)

interface SandboxProcFS {
    /** 当前可见的进程表；读不到的字段为 null（权限受限或进程已退出）。 */
    fun entries(): Map<Long, SandboxProcEntry>

    /** [port] 上处于 LISTEN 的套接字 inode 集合；网络表不可读时为空。 */
    fun listeningSocketInodes(port: Int): Set<Long>

    /** [pid] 打开的套接字 inode 集合；只有同 uid 的进程可读。 */
    fun socketInodes(pid: Long): Set<Long>
}

/** 真机实现：只读 `/proc` 下同 uid 可见的条目，读不到就当作没有。 */
object LinuxSandboxProcFS : SandboxProcFS {
    override fun entries(): Map<Long, SandboxProcEntry> = runCatching {
        File(PROC).listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toLongOrNull() ?: return@mapNotNull null
            val status = runCatching { File(entry, "status").readText() }.getOrNull() ?: return@mapNotNull null
            val parent = status.lineSequence()
                .firstOrNull { it.startsWith("PPid:") }?.substringAfter('\t')?.trim()?.toLong()
            pid to SandboxProcEntry(parent, linkTarget(entry, "exe"), linkTarget(entry, "cwd"), commandLineOf(entry))
        }.toMap()
    }.getOrDefault(emptyMap())

    override fun listeningSocketInodes(port: Int): Set<Long> = runCatching {
        val inodes = mutableSetOf<Long>()
        SOCKET_TABLES.forEach { path ->
            val table = File(path).takeIf { it.canRead() } ?: return@forEach
            table.forEachLine { line ->
                val fields = line.trim().split(WHITESPACE)
                if (fields.size < INODE_COLUMN || fields[STATE_COLUMN] != LISTEN_STATE) return@forEachLine
                if (fields[LOCAL_COLUMN].substringAfterLast(':').toIntOrNull(16) != port) return@forEachLine
                fields[INODE_COLUMN].toLongOrNull()?.let(inodes::add)
            }
        }
        inodes
    }.getOrDefault(emptySet())

    override fun socketInodes(pid: Long): Set<Long> = runCatching {
        File(PROC, "$pid/fd").listFiles().orEmpty().mapNotNullTo(mutableSetOf()) { fd ->
            val target = runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()
                ?: return@mapNotNullTo null
            target.removePrefix(SOCKET_PREFIX).removeSuffix(SOCKET_SUFFIX)
                .takeIf { target.startsWith(SOCKET_PREFIX) && target.endsWith(SOCKET_SUFFIX) }?.toLongOrNull()
        }
    }.getOrDefault(emptySet())

    /** `/proc/<pid>/exe` 与 `cwd` 都是符号链接；解析结果同时保留 `(deleted)` 后缀给上层剥离。 */
    private fun linkTarget(entry: File, name: String): String? =
        runCatching { File(entry, name).canonicalFile.absolutePath }.getOrNull()

    private fun commandLineOf(entry: File): String? =
        runCatching { File(entry, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private const val PROC = "/proc"
    private const val SOCKET_PREFIX = "socket:["
    private const val SOCKET_SUFFIX = "]"
    private const val LISTEN_STATE = "0A"

    // /proc/net/tcp{,6} 的列序：sl | local_address | rem_address | st | ... | inode
    private const val LOCAL_COLUMN = 1
    private const val STATE_COLUMN = 3
    private const val INODE_COLUMN = 9
    private val SOCKET_TABLES = listOf("/proc/net/tcp", "/proc/net/tcp6")
    private val WHITESPACE = Regex("\\s+")
}

/** 只能信号同 uid 进程；他 uid 的失败会被静默计为「没收到」。 */
private fun killOwnedProcess(pid: Long): Boolean = runCatching {
    AndroidProcess.killProcess(pid.toInt())
    true
}.getOrDefault(false)
