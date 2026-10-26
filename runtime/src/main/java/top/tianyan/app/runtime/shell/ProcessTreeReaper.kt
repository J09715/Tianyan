package top.tianyan.app.runtime.shell

import android.os.Process as AndroidProcess
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

    /** 递归收掉 [rootPid] 的后代并 SIGKILL 根本身，返回成功收掉的进程数。 */
    fun reap(rootPid: Long): Int
}

internal object SystemProcessTreeReaper : ProcessTreeReaper {
    override fun forkTid(): Int? =
        runCatching { AndroidProcess.myTid() }.getOrNull()?.takeIf { it > 0 }

    override fun childOfThread(tid: Int): Long? = runCatching {
        // children 按创建顺序排列，取最后一个即本次 fork 的子进程
        File("/proc/self/task/$tid/children").readText().trim().split(' ')
            .lastOrNull { it.isNotBlank() }?.toLong()
    }.getOrNull()

    override fun reap(rootPid: Long): Int = runCatching {
        val childrenOf = mutableMapOf<Long, MutableList<Long>>()
        readableChildren().forEach { (pid, ppid) -> childrenOf.getOrPut(ppid) { mutableListOf() } += pid }
        val doomed = mutableListOf<Long>()
        val pending = ArrayDeque(childrenOf[rootPid].orEmpty())
        while (pending.isNotEmpty()) {
            val pid = pending.removeFirst()
            doomed += pid
            pending += childrenOf[pid].orEmpty()
        }
        doomed += rootPid
        doomed.count { kill(it) }
    }.getOrDefault(0)

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
