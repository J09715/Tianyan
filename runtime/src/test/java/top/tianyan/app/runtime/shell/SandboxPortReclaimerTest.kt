package top.tianyan.app.runtime.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SandboxPortReclaimerTest {
    private val ownedRoot: File = Files.createTempDirectory("tianyan-reclaim").toFile()
    private val guestBinary = File(ownedRoot, "distros/ubuntu/rootfs/usr/bin/node").absolutePath
    private val guestShell = File(ownedRoot, "distros/ubuntu/rootfs/bin/sh").absolutePath
    private val tracerBinary = File(ownedRoot, "lib/arm64/libproot.so").absolutePath
    private val hostBinary = "/system/bin/app_process64"

    private class FakeProcFS(
        val entries: Map<Long, SandboxProcEntry> = emptyMap(),
        val listening: Map<Int, Set<Long>> = emptyMap(),
        val sockets: Map<Long, Set<Long>> = emptyMap(),
    ) : SandboxProcFS {
        override fun entries() = entries
        override fun listeningSocketInodes(port: Int) = listening[port].orEmpty()
        override fun socketInodes(pid: Long) = sockets[pid].orEmpty()
    }

    private fun reclaimer(fs: FakeProcFS, killed: MutableList<Long> = mutableListOf()) =
        SandboxPortReclaimer(listOf(ownedRoot), fs) { pid -> killed += pid; true }

    /** tracer 已消失（被 reparent 到 init）：监听者连同整棵树都被收回。 */
    @Test fun abandonedListenerOnThePortIsReclaimedWithItsTree() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(
                40L to SandboxProcEntry(1L, tracerBinary),
                41L to SandboxProcEntry(40L, guestShell),
                42L to SandboxProcEntry(41L, guestBinary),
            ),
            listening = mapOf(3065 to setOf(900L)),
            sockets = mapOf(42L to setOf(900L)),
        )
        assertEquals(3, reclaimer(fs, killed).reclaim(3065))
        assertEquals(listOf(40L, 41L, 42L), killed)
    }

    /**
     * 回归：托管网关的 tracer 就是宿主 App 的直接子进程。停止失败后它在登记表之外存活，
     * 早期实现把「根挂在宿主 App 下」当成「仍有主人」而拒绝回收，端口于是永远占着。
     */
    @Test fun listenerAnchoredOnlyToTheHostAppIsReclaimed() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(
                7L to SandboxProcEntry(1L, hostBinary),
                40L to SandboxProcEntry(7L, tracerBinary),
                41L to SandboxProcEntry(40L, guestShell),
                42L to SandboxProcEntry(41L, guestBinary),
            ),
            listening = mapOf(3065 to setOf(900L)),
            sockets = mapOf(42L to setOf(900L)),
        )
        assertEquals(3, reclaimer(fs, killed).reclaim(3065))
        assertEquals(listOf(40L, 41L, 42L), killed)
    }

    /** 终端里手起的服务：tracer 作为会话根被调用方豁免，整棵树放过。 */
    @Test fun listenerAnchoredToAProtectedSessionRootIsLeftAlone() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(
                7L to SandboxProcEntry(1L, hostBinary),
                40L to SandboxProcEntry(7L, tracerBinary),
                41L to SandboxProcEntry(40L, guestBinary),
            ),
            listening = mapOf(8080 to setOf(900L)),
            sockets = mapOf(41L to setOf(900L)),
        )
        assertEquals(0, reclaimer(fs, killed).reclaim(8080, protectedRoots = setOf(40L)))
        assertTrue(killed.isEmpty())
    }

    /** 端口上没有本运行时的监听者（他应用占用）：不碰任何东西。 */
    @Test fun foreignOrUnknownListenersAreNeverKilled() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(42L to SandboxProcEntry(1L, guestBinary)),
            listening = mapOf(3065 to setOf(900L)),
            sockets = emptyMap(),
        )
        assertEquals(0, reclaimer(fs, killed).reclaim(3065))
        assertTrue(killed.isEmpty())
    }

    /**
     * 网络表读不到（部分内核/SELinux 屏蔽 `/proc/net`）：退回「本运行时的全部进程」，
     * 但豁免名单仍然生效 —— 否则腾端口会连着终端和前台命令一起杀掉。
     */
    @Test fun unreadableSocketTableFallsBackToReclaimingOnlyAbandonedTrees() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(
                40L to SandboxProcEntry(1L, tracerBinary),
                41L to SandboxProcEntry(40L, guestBinary),
                60L to SandboxProcEntry(1L, tracerBinary),
                61L to SandboxProcEntry(60L, guestBinary),
            ),
            listening = emptyMap(),
            sockets = emptyMap(),
        )
        assertEquals(2, reclaimer(fs, killed).reclaim(3065, protectedRoots = setOf(60L)))
        assertEquals(listOf(40L, 41L), killed)
    }

    /** 二进制被替换成 `(deleted)`、或 exe 不可读时，退回 cwd / cmdline 判定归属。 */
    @Test fun ownershipFallsBackToWorkingDirectoryAndCommandLine() {
        val killed = mutableListOf<Long>()
        val fs = FakeProcFS(
            entries = mapOf(
                40L to SandboxProcEntry(1L, null, workingDirectory = File(ownedRoot, "distros/ubuntu/opt/tianyan/data/agent2api").absolutePath),
                41L to SandboxProcEntry(1L, null, commandLine = "$tracerBinary --kill-on-exit -r ${File(ownedRoot, "distros/ubuntu/rootfs").absolutePath}"),
            ),
            listening = mapOf(3065 to setOf(900L)),
            sockets = mapOf(40L to setOf(900L), 41L to setOf(900L)),
        )
        assertEquals(2, reclaimer(fs, killed).reclaim(3065))
        assertEquals(listOf(40L, 41L), killed)
    }

    @Test fun deletedMarkerAndParentlessEntriesStillCountAsOwnedOrphans() {
        val reaper = reclaimer(
            FakeProcFS(entries = mapOf(60L to SandboxProcEntry(null, "$guestBinary (deleted)"))),
        )
        // 无端口归属信息 → 兜底路径；父进程未知同样按被遗弃处理。
        assertEquals(1, reaper.reclaim(3065))
    }

    @Test fun nothingIsReclaimedWithoutPerProcessFacts() {
        val blind = SandboxPortReclaimer(listOf(ownedRoot), FakeProcFS()) { error("must not signal") }
        assertEquals(0, blind.reclaim(3065))
    }
}
