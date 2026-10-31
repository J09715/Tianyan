package top.tianyan.app.harness

import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** App-uid literal search; the caller first resolves the requested path with its normal workspace policy. */
internal class WorkspaceTextSearch(
    private val walk: (Path, SimpleFileVisitor<Path>) -> Unit = { root, visitor -> Files.walkFileTree(root, emptySet(), 16, visitor); Unit },
    private val clock: () -> Long = System::nanoTime,
) {
    suspend fun search(root: File, query: String, maxResults: Int, redactLine: (String) -> String = { it }): String {
        require(query.isNotBlank() && query.length <= 256 && query.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "query 必须为 1–256 字符的非空单行字面文本"
        }
        require(maxResults in 1..100) { "max_results 必须为 1–100" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val start = clock()
        val base = root.toPath().toRealPath()
        val labelBase = if (root.isDirectory) base else base.parent
        val matches = mutableListOf<String>()
        var entries = 0
        var bytes = 0L
        var skipped = 0
        var stopped = false
        fun active(): Boolean {
            context.ensureActive()
            val allowed = entries <= MAX_ENTRIES && bytes < MAX_TOTAL_BYTES &&
                matches.size < maxResults && clock() - start < MAX_NANOS
            if (!allowed) stopped = true
            return allowed
        }
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                entries++
                if (!active()) return FileVisitResult.TERMINATE
                if (dir != base && dir.fileName.toString() in EXCLUDED) return FileVisitResult.SKIP_SUBTREE
                // NOFOLLOW traversal plus real-path confinement; concurrent mutation is not an atomic snapshot.
                if (!dir.toRealPath().startsWith(base)) {
                    skipped++; return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                entries++
                if (!active()) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile || attrs.size() > MAX_FILE_BYTES) {
                    skipped++; return FileVisitResult.CONTINUE
                }
                try {
                    if (!file.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(base)) {
                        skipped++; return FileVisitResult.CONTINUE
                    }
                    val content = Channels.newInputStream(Files.newByteChannel(file,
                        setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (active()) {
                            val size = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_TOTAL_BYTES - bytes).toInt())
                            if (size < 0) break
                            bytes += size
                            output.write(buffer, 0, size)
                            if (output.size() > MAX_FILE_BYTES) { skipped++; return FileVisitResult.CONTINUE }
                        }
                        if (stopped) return FileVisitResult.TERMINATE
                        output.toByteArray()
                    }
                    if (content.any { it == 0.toByte() }) { skipped++; return FileVisitResult.CONTINUE }
                    val relative = labelBase.relativize(file).toString().replace('\\', '/')
                    content.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                        if (!active()) return FileVisitResult.TERMINATE
                        if (line.contains(query)) {
                            val redacted = redactLine(line)
                            val position = redacted.indexOf(query).takeIf { it >= 0 } ?: line.indexOf(query).coerceAtMost(redacted.length)
                            val excerpt = redacted.substring((position - 80).coerceAtLeast(0)).take(400)
                            matches += "$relative:${index + 1}: $excerpt"
                            if (!active()) return FileVisitResult.TERMINATE
                        }
                    }
                } catch (_: IOException) { skipped++ }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                entries++; skipped++
                return if (active()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }
        }
        walk(base, visitor)
        return buildString {
            append("[字面检索：路径相对于请求目录（单文件为父目录）；命中 ${matches.size}；读取 $bytes 字节；跳过 $skipped 项；")
            append(if (stopped || skipped > 0) "结果不完整" else "范围内扫描结束")
            append("；最大深度 16；排除 ${EXCLUDED.joinToString(",")}；每行仅展示命中附近最多 400 字符]\n")
            append(matches.joinToString("\n").ifBlank { "未发现匹配（受上述范围与限额约束）" })
        }
    }

    private companion object {
        const val MAX_ENTRIES = 5000
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024L
        const val MAX_NANOS = 5_000_000_000L
        val EXCLUDED = setOf(".git", ".gradle", "node_modules", "build", ".tianyan-outputs")
    }
}
