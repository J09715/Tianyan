package top.tianyan.app.runtime.uml

import top.tianyan.app.core.common.logging.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UML 引擎前置探测：在引导整颗 guest 之前，先用 64MB 小内存 + 无 rootfs 起内核，
 * 判定本机内核/页大小/seccomp 能否跑 UML。移植自 Stryker UmlProbe，三态结论：
 * - [Verdict.KERNEL_STARTS]：能跑；
 * - [Verdict.UNPROVEN]：没在超时内判定（设备慢 / 卡在 mount）；
 * - [Verdict.BLOCKED]：明确跑不了（缺二进制、MADV_REMOVE 无 tmpfs、16KB 页等）。
 *
 * Android 上最大的拦路虎是「应用拿不到可写 tmpfs」→ guest 内存落不到 RAM → UML 拒跑，
 * 此时 Stryker 给 QEMU 兜底。天衍侧把 BLOCKED 映射成「本设备不适合 UML，建议 PRoot」。
 */
@Singleton
class UmlProbe @Inject constructor(
    private val layout: UmlRuntimeLayout,
    private val logger: AppLogger,
) {
    enum class Verdict { KERNEL_STARTS, UNPROVEN, BLOCKED }

    class Result(
        val verdict: Verdict,
        val detail: String,
        val notes: List<String> = emptyList(),
    ) {
        fun ruledOut(): Boolean = verdict == Verdict.BLOCKED
    }

    fun run(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val kernel = layout.kernel
        val stub = layout.stub
        if (!kernel.canExecute()) {
            return Result(Verdict.BLOCKED, "UML kernel (${kernel.name}) missing or not executable")
        }
        if (!stub.exists()) {
            return Result(Verdict.BLOCKED, "UML address-space stub (${stub.name}) missing")
        }

        val cmd = listOf(
            kernel.absolutePath,
            "mem=${PROBE_MEM_MB}M",
            "con=null",
            "con0=fd:0,fd:1",
            "stub_exe=${stub.absolutePath}",
            "seccomp=auto",
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(layout.baseDir)
        pb.redirectErrorStream(true)
        val tmp = layout.baseDir.absolutePath
        pb.environment()["TMPDIR"] = tmp

        val process: java.lang.Process
        try {
            process = pb.start()
        } catch (e: Exception) {
            return Result(Verdict.BLOCKED, "kernel would not start: ${e.message}", emptyList())
        }

        val scan = Scan()
        val pump = Thread({
            // process.destroy() 会从另一线程关掉输入流，阻塞在 readLine() 的读端会抛
            // InterruptedIOException —— 这是「探测结束」的正常收尾，不是错误。
            // 线程未捕获异常会直接崩掉整个进程，所以必须吞掉。
            try {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { raw -> runCatching { scan.accept(raw.trim().takeIf { it.isNotEmpty() }) } }
                }
            } catch (_: java.io.InterruptedIOException) {
                // 正常收尾：探测超时/判定完成，进程被 destroy
            } catch (_: Exception) {
                // 读端被关、流中断等，同样视为 EOF
            }
        }, "tianyan-uml-probe")
        pump.isDaemon = true
        pump.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (scan.decided()) break
            if (!process.isAlive) break
            Thread.sleep(100)
        }
        runCatching { process.destroy() }
        pump.join(500)

        return scan.verdict(process.exitValue()).let { r -> withPageSize(r) }
    }

    private fun withPageSize(result: Result): Result {
        if (result.verdict == Verdict.KERNEL_STARTS) return result
        val page = hostPageSize()
        if (page in 1 until 4096 || page > 4096) {
            return Result(result.verdict, "${result.detail} · 内核页大小 ${page / 1024}KB（UML 需 4KB）", result.notes)
        }
        return result
    }

    private fun hostPageSize(): Long =
        runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)

    private class Scan {
        private val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile private var banner = false
        @Volatile private var completed = false
        @Volatile private var blocker: String? = null
        @Volatile private var mode = ""

        fun accept(line: String?) {
            if (line == null) return
            if (line.contains("Linux version")) banner = true
            if (line.contains("Unable to mount root fs") || line.contains("VFS: Cannot open root device") ||
                line.contains("Kernel panic")) {
                banner = true
                completed = true
            }
            if (line.contains("Userspace mode:")) {
                mode = line
                keep(line)
            }
            if (line.contains("tempdir") || line.contains("PROT_EXEC") || line.startsWith("Checking")) {
                keep(line)
            }
            if (banner) return
            when {
                line.contains("none found") -> blocked("no writable tempdir for the guest's memory")
                line.contains("MADV_REMOVE") -> blocked("kernel cannot back guest memory (needs tmpfs) — " + line)
                line.contains("Failed to") || line.contains("Aborted") || line.contains("Illegal instruction") ||
                    line.contains("Permission denied") || line.contains("not permitted") ->
                    blocked(line.also { keep(it) })
            }
        }

        private fun keep(line: String) {
            if (notes.size < 24) notes.add(line)
        }

        private fun blocked(why: String) {
            if (blocker == null) blocker = why
        }

        fun decided(): Boolean = completed || (blocker != null && !banner)

        fun verdict(exit: Int): Result {
            return when {
                completed -> Result(Verdict.KERNEL_STARTS, if (mode.isEmpty()) "the kernel starts here" else "kernel starts · ${shortMode()}")
                banner -> Result(Verdict.KERNEL_STARTS, "the kernel starts here")
                else -> Result(Verdict.BLOCKED, blocker ?: "the kernel exited immediately (exit $exit)", notes.toList())
            }
        }

        private fun shortMode(): String {
            val at = mode.indexOf("Userspace mode:")
            val tail = if (at < 0) mode else mode.substring(at)
            return if (tail.length > 48) tail.substring(0, 48) else tail
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val PROBE_MEM_MB = 64
    }
}
