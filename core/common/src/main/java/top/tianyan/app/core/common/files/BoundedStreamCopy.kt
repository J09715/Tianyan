package top.tianyan.app.core.common.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 流体积超限异常。当数据流超过允许的最大字节数且策略为 [BoundedStreamCopy.OverflowPolicy.ABORT] 时抛出。
 */
class PayloadTooLargeException(
    val maxBytes: Long,
    val actualBytesObserved: Long,
    message: String = "数据流大小超过上限（允许最大 ${maxBytes / 1024} KB，实际已达 ${actualBytesObserved / 1024} KB）",
) : IOException(message)

/**
 * 通用有界流式传输与复制安全工具库（借鉴 PalmClaw BoundedStreamCopy 设计）。
 *
 * 核心目标：
 * 1. 规避 Android JVM 堆内存（256MB~512MB）压力，以固定块（默认 16KB）流式传输，绝不在内存中全量缓冲；
 * 2. 杜绝无界 IO 写入造成移动端本地存储撑爆或 DoS 攻击；
 * 3. 支持超限自动清理（避免磁盘残留损坏的半截大文件）或确定性截断。
 */
object BoundedStreamCopy {

    /** 默认紧凑流缓冲区大小：16KB，兼顾吞吐量与极低内存占用 */
    const val DEFAULT_BUFFER_SIZE = 16 * 1024

    /** 默认附件最大单文件大小：200MB（TianyanDev 迭代：由 50MB 上调，支持大产物附件） */
    const val DEFAULT_MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024

    /** 默认通用拷贝上限：64MB */
    const val DEFAULT_MAX_COPY_BYTES = 64L * 1024 * 1024

    enum class OverflowPolicy {
        /** 超限时立即中断拷贝，若写向目标文件则清理该文件，并抛出 [PayloadTooLargeException] */
        ABORT,

        /** 超限时平滑停止写入，返回已写入字节数，标记 [CopyResult.isTruncated] = true，不抛异常 */
        TRUNCATE,
    }

    data class CopyResult(
        val bytesCopied: Long,
        val isTruncated: Boolean,
        val reachedEof: Boolean,
    )

    /**
     * 从输入流 [input] 向输出流 [output] 有界流式拷贝字节数据。
     *
     * @param maxBytes 允许拷贝的最大字节数
     * @param bufferSize 每次读取的缓冲区大小
     * @param policy 超过 [maxBytes] 时的处理策略
     * @param onProgress 每次分块写入后的进度通知回调（接收累计已写字节数）
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = DEFAULT_MAX_COPY_BYTES,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        policy: OverflowPolicy = OverflowPolicy.ABORT,
        onProgress: ((bytesWritten: Long) -> Unit)? = null,
    ): CopyResult {
        require(maxBytes >= 0) { "maxBytes must be non-negative, but was $maxBytes" }
        require(bufferSize > 0) { "bufferSize must be positive, but was $bufferSize" }

        val buffer = ByteArray(bufferSize)
        var totalWritten = 0L

        while (true) {
            val remaining = maxBytes - totalWritten
            if (remaining <= 0) {
                // 已达上限：探测输入流是否已经恰好到底
                val probe = input.read()
                if (probe == -1) {
                    output.flush()
                    return CopyResult(bytesCopied = totalWritten, isTruncated = false, reachedEof = true)
                }
                // 确实超限
                return when (policy) {
                    OverflowPolicy.ABORT -> throw PayloadTooLargeException(
                        maxBytes = maxBytes,
                        actualBytesObserved = totalWritten + 1,
                    )
                    OverflowPolicy.TRUNCATE -> {
                        output.flush()
                        CopyResult(
                            bytesCopied = totalWritten,
                            isTruncated = true,
                            reachedEof = false,
                        )
                    }
                }
            }

            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val bytesRead = input.read(buffer, 0, toRead)
            if (bytesRead == -1) {
                output.flush()
                return CopyResult(bytesCopied = totalWritten, isTruncated = false, reachedEof = true)
            }

            output.write(buffer, 0, bytesRead)
            totalWritten += bytesRead
            onProgress?.invoke(totalWritten)
        }
    }

    /**
     * 将输入流 [input] 安全拷贝至目标本地文件 [targetFile]。
     * 若发生 [PayloadTooLargeException] 或 IO 异常，且 [policy] 为 [OverflowPolicy.ABORT]，
     * 自动删除目标文件，保证不会在磁盘残留损坏或超大脏文件。
     */
    fun copyToFile(
        input: InputStream,
        targetFile: File,
        maxBytes: Long = DEFAULT_MAX_ATTACHMENT_BYTES,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        policy: OverflowPolicy = OverflowPolicy.ABORT,
        onProgress: ((bytesWritten: Long) -> Unit)? = null,
    ): CopyResult {
        targetFile.parentFile?.mkdirs()
        var completedCleanly = false
        return try {
            FileOutputStream(targetFile).use { output ->
                copy(
                    input = input,
                    output = output,
                    maxBytes = maxBytes,
                    bufferSize = bufferSize,
                    policy = policy,
                    onProgress = onProgress,
                ).also {
                    completedCleanly = true
                }
            }
        } finally {
            if (!completedCleanly) {
                runCatching { targetFile.delete() }
            }
        }
    }

    /**
     * 本地文件之间的安全有界拷贝。
     */
    fun copyBounded(
        source: File,
        target: File,
        maxBytes: Long = DEFAULT_MAX_COPY_BYTES,
        policy: OverflowPolicy = OverflowPolicy.ABORT,
    ): CopyResult {
        return source.inputStream().use { input ->
            copyToFile(
                input = input,
                targetFile = target,
                maxBytes = maxBytes,
                policy = policy,
            )
        }
    }
}
