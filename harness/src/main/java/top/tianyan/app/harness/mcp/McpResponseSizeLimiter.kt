package top.tianyan.app.harness.mcp

import okhttp3.Response
import top.tianyan.app.core.common.files.BoundedStreamCopy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * MCP 协议报文大小熔断与流式转存限制器（借鉴 PalmClaw McpResponseSizeLimiter 设计）。
 *
 * ## 移动端内存防御阶梯 (Boundary Defense)
 * 1. **内联安全区 (< 512KB)**：
 *    数据量安全，在内存中直接缓冲并完成 JSON 反序列化，延迟最低；
 * 2. **落盘引流区 (512KB ~ 4MB)**：
 *    数据过大，如果在堆中拼装大 String 并解析 JSON AST 会造成显著 GC 冻结甚至 OOM。
 *    采用 16KB 分块流式直接写出至工作区/缓存落盘文件，并向调用方/模型返回相对路径与轻量预览（前 2KB）；
 * 3. **硬件熔断硬顶 (> 4MB)**：
 *    针对异常失控的死循环流或远程服务恶意/畸形巨型报文，触发熔断器，立即终止流读取，
 *    清理不完整的残余文件，释放连接与进程管道，返回结构化拦截说明，阻断内存打爆。
 */
object McpResponseSizeLimiter {

    /** 允许在内存中内联缓冲解析的最大体积：512KB */
    const val DEFAULT_MAX_INLINE_BYTES = 512L * 1024

    /** 允许流式落盘转存的最大上限 / 熔断阈值：4MB */
    const val DEFAULT_MAX_SPILL_BYTES = 4L * 1024 * 1024

    /**
     * SSE 路径的累积流硬顶：8MB（仅 McpHttpTransport 的 SSE 读取使用）。
     * 非 SSE 路径没有这一级：内联超限后直接进入落盘区，落盘超 [DEFAULT_MAX_SPILL_BYTES] 即熔断。
     */
    const val DEFAULT_HARD_LIMIT_BYTES = 8L * 1024 * 1024

    /** 轻量文本预览字符上限 */
    const val PREVIEW_MAX_CHARS = 2048

    /** spill 目录总容量配额（与 ToolOutputSpillStore 对齐） */
    private const val MAX_SPILL_DIR_BYTES = 64L * 1024 * 1024

    /** spill 目录文件数配额 */
    private const val MAX_SPILL_DIR_FILES = 64

    sealed interface Payload {
        /** 安全内联字符串数据 */
        data class Inline(val text: String, val bytes: Long) : Payload

        /** 已安全流式转存至磁盘，附带轻量预览 */
        data class Spilled(
            val file: File,
            val totalBytes: Long,
            val previewText: String,
        ) : Payload {
            fun formatForToolResult(): String = buildString {
                append("[MCP 响应数据量较大（")
                append(totalBytes / 1024)
                append(" KB），为保护移动端内存已自动流式转存至文件: ")
                append(file.absolutePath)
                append("]\n\n[内容预览]：\n")
                append(previewText)
                append("\n\n【提示】：请根据上方内容预览分析结果，或在后续工具调用中增加过滤条件/缩小请求范围。")
            }
        }

        /** 触发硬熔断拦截 */
        data class CircuitBroken(
            val reason: String,
            val bytesObserved: Long,
        ) : Payload {
            fun formatErrorMessage(): String =
                "[MCP 响应熔断拦截：$reason（已观测 $bytesObserved 字节），已强制中断流式传输以保护应用内存]"
        }

        /**
         * 落盘转存不可用：本机环境/配置问题（未注入 spillDirectory、临时目录不可写等）。
         * 与 [CircuitBroken] 语义严格区分——这不是「对方数据过大」，重试或缩小请求范围都无法恢复，
         * 上层应把原因如实透出给用户/模型，而不是让配置错误被伪装成响应过大。
         */
        data class SpillUnavailable(
            val reason: String,
            val bytesObserved: Long,
        ) : Payload {
            fun formatErrorMessage(): String =
                "[MCP 响应转存失败：$reason（已观测 $bytesObserved 字节）。这是本机落盘环境问题而非响应过大，重试无法恢复，请检查 MCP 传输的落盘目录配置]"
        }
    }

    /**
     * 对输入流 [input] 进行有界流式读取。
     */
    fun readBounded(
        input: InputStream,
        maxInlineBytes: Long = DEFAULT_MAX_INLINE_BYTES,
        maxSpillBytes: Long = DEFAULT_MAX_SPILL_BYTES,
        spillDirectory: File? = null,
        spillFilePrefix: String = "mcp_spill_",
    ): Payload {
        val memoryBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(BoundedStreamCopy.DEFAULT_BUFFER_SIZE)
        var totalBytesRead = 0L

        // 第一阶段：读取内联安全阈值内的数据
        while (totalBytesRead < maxInlineBytes) {
            val toRead = minOf(chunk.size.toLong(), maxInlineBytes - totalBytesRead).toInt()
            val read = input.read(chunk, 0, toRead)
            if (read == -1) {
                return Payload.Inline(
                    text = memoryBuffer.toString(Charsets.UTF_8.name()),
                    bytes = totalBytesRead,
                )
            }
            memoryBuffer.write(chunk, 0, read)
            totalBytesRead += read
        }

        // 探测是否刚好到达 EOF
        val probe = input.read()
        if (probe == -1) {
            return Payload.Inline(
                text = memoryBuffer.toString(Charsets.UTF_8.name()),
                bytes = totalBytesRead,
            )
        }

        // 第二阶段：超过内联限额，启动流式落盘转存
        val inlineText = memoryBuffer.toString(Charsets.UTF_8.name())
        val preview = inlineText.take(PREVIEW_MAX_CHARS)

        val targetDir = resolveSpillDirectory(spillDirectory)
            ?: return Payload.SpillUnavailable(
                reason = "无可用的落盘转存目录（未注入 spillDirectory 且系统临时目录不可写）",
                bytesObserved = totalBytesRead,
            )
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return Payload.SpillUnavailable(
                reason = "落盘转存目录创建失败: ${targetDir.absolutePath}",
                bytesObserved = totalBytesRead,
            )
        }
        cleanupOldSpills(targetDir, spillFilePrefix)
        val spillFile = try {
            File.createTempFile(spillFilePrefix, ".txt", targetDir)
        } catch (_: Throwable) {
            return Payload.SpillUnavailable(
                reason = "落盘转存文件创建失败: ${targetDir.absolutePath}",
                bytesObserved = totalBytesRead,
            )
        }

        var completedCleanly = false
        try {
            FileOutputStream(spillFile).use { fileOut ->
                memoryBuffer.writeTo(fileOut)
                fileOut.write(probe)
                totalBytesRead += 1

                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    totalBytesRead += read

                    if (totalBytesRead > maxSpillBytes) {
                        // 超过最大允许上限：触发硬熔断，清理不完整的残余文件
                        return Payload.CircuitBroken(
                            reason = "输出超过单次安全上限 ${maxSpillBytes / 1024}KB",
                            bytesObserved = totalBytesRead,
                        )
                    }

                    fileOut.write(chunk, 0, read)
                }
            }
            completedCleanly = true
            return Payload.Spilled(
                file = spillFile,
                totalBytes = totalBytesRead,
                previewText = preview,
            )
        } finally {
            if (!completedCleanly) {
                runCatching { spillFile.delete() }
            }
        }
    }

    private fun resolveSpillDirectory(spillDirectory: File?): File? {
        spillDirectory?.let { return it }
        val tmp = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (tmp != null && tmp.isDirectory && tmp.canWrite()) File(tmp, "tianyan_mcp_spills") else null
    }

    /**
     * 获取一个可用的落盘转存文件（目录解析 → 配额回收 → 建文件），供多条通道复用同一套配额策略。
     * 不可用（目录不可写/创建失败）时返回 null，调用方自行回退熔断路径。
     */
    internal fun acquireSpillFile(spillDirectory: File?, spillFilePrefix: String = "mcp_spill_"): File? {
        val targetDir = resolveSpillDirectory(spillDirectory) ?: return null
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return null
        cleanupOldSpills(targetDir, spillFilePrefix)
        return try {
            File.createTempFile(spillFilePrefix, ".txt", targetDir)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 落盘配额回收：先按过期时间清理，再按「总量 + 条数」上限从最旧开始淘汰。
     * 仅靠 24h 过期回收的话，短时间连续大响应可累积 N × 4MB，撑爆移动端磁盘。
     */
    private fun cleanupOldSpills(
        dir: File,
        prefix: String,
        maxAgeMs: Long = 24 * 60 * 60 * 1000L,
        maxTotalBytes: Long = MAX_SPILL_DIR_BYTES,
        maxFiles: Int = MAX_SPILL_DIR_FILES,
    ) {
        runCatching {
            val now = System.currentTimeMillis()
            val spills = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) }
                .orEmpty()
            spills.filter { now - it.lastModified() > maxAgeMs }.forEach { it.delete() }

            val remaining = spills.filter { it.exists() }.sortedBy { it.lastModified() }
            var total = remaining.sumOf { it.length() }
            var count = remaining.size
            for (file in remaining) {
                if (total <= maxTotalBytes && count <= maxFiles) break
                if (file.delete()) {
                    total -= file.length()
                    count--
                }
            }
        }
    }

    /**
     * 针对 OkHttp [Response] 的快速预检与流式读取。
     */
    fun readResponse(
        response: Response,
        maxInlineBytes: Long = DEFAULT_MAX_INLINE_BYTES,
        maxSpillBytes: Long = DEFAULT_MAX_SPILL_BYTES,
        spillDirectory: File? = null,
    ): Payload {
        val contentLength = response.body.contentLength()
        if (contentLength > maxSpillBytes) {
            return Payload.CircuitBroken(
                reason = "Content-Length ($contentLength 字节) 超过单次安全上限 ${maxSpillBytes / 1024}KB",
                bytesObserved = contentLength,
            )
        }

        return response.body.byteStream().use { stream ->
            readBounded(
                input = stream,
                maxInlineBytes = maxInlineBytes,
                maxSpillBytes = maxSpillBytes,
                spillDirectory = spillDirectory,
            )
        }
    }
}
