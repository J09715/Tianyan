package top.tianyan.app.runtime.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bootstrap/preflight commands must not inherit the target command's capture. */
suspend fun <T> ShellCommand.withOutputCapture(execute: suspend () -> T): T {
    val capture = outputCapture
    return if (capture == null) execute() else withContext(capture) { execute() }
}

/** Opt-in foreground capture. Raw spools stay in private cache until the caller redacts the result. */
class CommandOutputCapture : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<CommandOutputCapture>
    private var streams: Pair<CommandStreamCapture, CommandStreamCapture>? = null

    internal fun start(directory: File): Pair<CommandStreamCapture, CommandStreamCapture> {
        check(streams == null) { "输出捕获不能用于多个命令" }
        return (CommandStreamCapture(directory) to CommandStreamCapture(directory)).also { streams = it }
    }

    suspend fun expand(result: CommandResult): CommandResult = withContext(Dispatchers.IO) {
        val captured = streams ?: return@withContext result
        val stderr = captured.second.text(result.stderr)
        val diagnostic = result.outputDiagnostic
        result.copy(stdout = captured.first.text(result.stdout), stderr =
            if (diagnostic == null || stderr.endsWith(diagnostic)) stderr else stderr.trimEnd() + "\n" + diagnostic)
    }

    override fun close() {
        streams?.let { (stdout, stderr) -> try { stdout.close() } finally { stderr.close() } }
    }
}

/** Each stream keeps a small inline prefix, a bounded disk prefix and the last diagnostic lines. */
internal class CommandStreamCapture(
    private val directory: File,
    private val inlineBytes: Int = 64 * 1024,
    private val maxBytes: Int = 6 * 1024 * 1024,
    private val tailBytes: Int = 64 * 1024,
) : AutoCloseable {
    private val inline = ByteArrayOutputStream()
    private val tail = ByteArray(tailBytes)
    private var tailSize = 0
    private var tailNext = 0
    private var total = 0L
    private var kept = 0
    private var file: File? = null
    private var sink: FileOutputStream? = null
    private var failed = false
    private var closed = false
    private var eof = false

    @Synchronized fun finished() { eof = true }

    @Synchronized fun add(bytes: ByteArray, count: Int) {
        check(!closed)
        total += count
        if (count >= tailBytes) {
            System.arraycopy(bytes, count - tailBytes, tail, 0, tailBytes)
            tailNext = 0
        } else {
            val first = minOf(count, tailBytes - tailNext)
            System.arraycopy(bytes, 0, tail, tailNext, first)
            System.arraycopy(bytes, first, tail, 0, count - first)
            tailNext = (tailNext + count) % tailBytes
        }
        tailSize = minOf(tailBytes, tailSize + count)
        if (failed || kept >= maxBytes) return
        try {
            if (file == null && kept + count > inlineBytes) {
                file = allocate(directory, maxBytes)
                sink = FileOutputStream(file!!)
                inline.writeTo(sink!!)
                inline.reset()
            }
            val retain = minOf(count, maxBytes - kept)
            (sink ?: inline).write(bytes, 0, retain)
            kept += retain
        } catch (_: IOException) {
            failed = true // Disk failure must never stop draining the child process.
            try { sink?.close() } catch (_: IOException) { }
            sink = null
        }
    }

    @Synchronized fun text(fallback: String): String {
        try { sink?.flush() } catch (_: IOException) { failed = true }
        if (failed) return fallback + "\n[完整输出缓存不可用，仅返回有界预览；请缩小查询范围。]"
        val prefix = try { file?.readText(Charsets.UTF_8) ?: inline.toString(Charsets.UTF_8.name()) }
        catch (_: IOException) { return fallback + "\n[完整输出缓存读取失败，仅返回有界预览。]" }
        val incomplete = if (eof) "" else "\n[输出管道未完全排空，仅保存已读取部分。]"
        if (total <= kept) return prefix + incomplete
        // Drop cut lines before redaction so a secret cut at a storage boundary cannot leak fragments.
        val safeHead = prefix.substringBeforeLast('\n', "")
        val orderedTail = ByteArray(tailSize) { tail[(tailNext - tailSize + it + tailBytes) % tailBytes] }
        val safeTail = orderedTail.toString(Charsets.UTF_8).substringAfter('\n', "")
        return "$safeHead\n[输出保存达到上限：共 $total 字节，前缀上限 $maxBytes 字节；中段可能缺失，以下保留末尾完整行。]\n$safeTail$incomplete"
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { sink?.close() } catch (_: IOException) { } finally {
            sink = null
            file?.let { release(it) }
            inline.reset()
        }
    }

    private companion object {
        const val MAX_RESERVED_BYTES = 64L * 1024 * 1024
        val active = mutableMapOf<File, Int>()

        @Synchronized fun allocate(directory: File, reserve: Int): File {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建输出缓存")
            // Process death leaves no live owners. Reclaim only our unowned spool files on the next capture.
            val entries = directory.listFiles() ?: throw IOException("无法枚举输出缓存")
            entries.filter { it.name.startsWith("command-") && it.name.endsWith(".tmp") && it !in active }
                .forEach { if (!it.delete()) throw IOException("无法回收输出缓存") }
            if (active.values.sumOf { it.toLong() } + reserve > MAX_RESERVED_BYTES) throw IOException("输出缓存配额不足")
            return File.createTempFile("command-", ".tmp", directory).also { active[it] = reserve }
        }

        @Synchronized fun release(file: File) {
            file.delete()
            active.remove(file)
        }
    }
}
