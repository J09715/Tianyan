package top.tianyan.app.runtime.shell

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 收尾失败时的后代进程回收。
 *
 * Java 的 [Process.destroyForcibly] 只作用于直接子进程；一旦 PRoot tracer 卡在 tracee 等待上，
 * SIGKILL 之后其 tracee（`bash -l` 的子进程、gradle 后代等）会被 reparent 到 init 永久存活，
 * 而 App 侧再无手段回收。这里只在「SIGKILL 后仍未退出」这一失败路径上，先枚举该进程的后代
 * 逐个收掉，避免孤儿进程组累积。全部走 /proc 与同 uid 的 SIGKILL，不依赖 native。
 */
internal interface ProcessTreeReaper {
    /** fork 子进程前记录当前线程 tid：/proc 以线程粒度记录 children，据此精确定位本线程的子进程。 */
    fun forkTid(): Int?

    /** 定位 [tid] 线程 fork 出的直接子进程 pid；不可得时返回 null（非 Linux / 权限受限 / 单元测试）。 */
    fun childOfThread(tid: Int): Long?

    /**
     * 快照 [rootPid] 的整棵后代。**必须在杀根进程之前调用**：根一死，后代被内核 reparent 到 init，
     * 再从 PPid 子树就永远找不到它们 —— 停掉网关后仍占着端口的残留监听者正是这样留下的。
     */
    fun descendantsOf(rootPid: Long): List<Long>

    /**
     * 目标进程所在进程组；与宿主 App 同组或不可得时返回 null。
     *
     * 网关（agent2api / cc-switch 等）常常自己 setsid/setpgid，把 host 与 server 放进新进程组；
     * 只信号根 PID 会把整组留给 init。但若子进程继承的是**我们自己的进程组**，整组信号会连带
     * 杀掉宿主 App，所以必须把「与自身同组」挡在外面。
     */
    fun processGroupOf(pid: Long): Long? = null

    /** 向进程组投递信号（内部取负 pid）；返回是否成功投递。 */
    fun signalGroup(processGroup: Long, force: Boolean): Boolean = false

    /** 递归收掉 [rootPid] 的后代并 SIGKILL 根本身，返回成功收掉的进程数。 */
    fun reap(rootPid: Long): Int

    /** 收掉 [descendantsOf] 快照中仍在命的后代；已退出或非本 uid 的静默跳过，返回成功收掉的进程数。 */
    fun reapDetached(descendants: List<Long>): Int
}

internal object SystemProcessTreeReaper : ProcessTreeReaper {
    override fun forkTid(): Int? =
        runCatching { AndroidProcess.myTid() }.getOrNull()?.takeIf { it > 0 }

    override fun childOfThread(tid: Int): Long? = runCatching {
        // children 按创建顺序排列，取最后一个即本次 fork 的子进程
        File("/proc/self/task/$tid/children").readText().trim().split(' ')
            .lastOrNull { it.isNotBlank() }?.toLong()
    }.getOrNull()

    override fun descendantsOf(rootPid: Long): List<Long> = runCatching {
        collectDescendants(childrenByParent(), rootPid)
    }.getOrDefault(emptyList())

    override fun processGroupOf(pid: Long): Long? = runCatching {
        val group = processGroupFrom(File("/proc/$pid/stat"))
        val own = processGroupFrom(File("/proc/self/stat"))
        group?.takeIf { it > 0 && it != own }
    }.getOrNull()

    override fun signalGroup(processGroup: Long, force: Boolean): Boolean = runCatching {
        if (processGroup <= 0 || processGroup > Int.MAX_VALUE) return@runCatching false
        Os.kill(-processGroup.toInt(), if (force) OsConstants.SIGKILL else OsConstants.SIGTERM)
        true
    }.getOrDefault(false)

    /** `/proc/<pid>/stat` = `pid (comm) state ppid pgrp ...`；comm 可能含空格，所以从最后一个 `)` 之后取。 */
    private fun processGroupFrom(statFile: File): Long? = runCatching {
        statFile.readText().substringAfterLast(')').trim().split(' ').getOrNull(2)?.toLong()
    }.getOrNull()

    override fun reap(rootPid: Long): Int = runCatching {
        val childrenOf = childrenByParent()
        (collectDescendants(childrenOf, rootPid) + rootPid).count { kill(it) }
    }.getOrDefault(0)

    override fun reapDetached(descendants: List<Long>): Int = descendants.count { kill(it) }

    /** pid -> 直接子进程列表；根进程死亡后该映射立即失真，故只在杀之前用它做快照。 */
    private fun childrenByParent(): Map<Long, List<Long>> {
        val childrenOf = mutableMapOf<Long, MutableList<Long>>()
        readableChildren().forEach { (pid, ppid) -> childrenOf.getOrPut(ppid) { mutableListOf() } += pid }
        return childrenOf
    }

    private fun collectDescendants(childrenOf: Map<Long, List<Long>>, rootPid: Long): List<Long> {
        val found = mutableListOf<Long>()
        val pending = ArrayDeque(childrenOf[rootPid].orEmpty())
        while (pending.isNotEmpty()) {
            val pid = pending.removeFirst()
            found += pid
            pending += childrenOf[pid].orEmpty()
        }
        return found
    }

    /** pid -> PPid 映射；只取同 uid 可读的条目，读不到的（他 uid / 已退出 / 无权限）直接跳过。 */
    private fun readableChildren(): List<Pair<Long, Long>> =
        File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toLongOrNull() ?: return@mapNotNull null
            val ppid = runCatching {
                File(entry, "status").readText().lineSequence()
                    .firstOrNull { it.startsWith("PPid:") }?.substringAfter('\t')?.trim()?.toLong()
            }.getOrNull() ?: return@mapNotNull null
            pid to ppid
        }

    /** 对目标 pid 发 SIGKILL；非本 uid 进程会抛异常被跳过，天然保证只能收掉自家进程。 */
    private fun kill(pid: Long): Boolean = runCatching {
        AndroidProcess.killProcess(pid.toInt())
        true
    }.getOrDefault(false)
}
