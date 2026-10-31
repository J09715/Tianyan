package top.tianyan.app.core.common.files

import java.io.File
import java.security.MessageDigest

/** 流式摘要：交付卡片的产物在分享/另存前要核对 sha256，不能整份读进内存。 */
object FileDigest {
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * @param maxBytes 允许参与计算的最大字节数；超过即返回 null（调用方按"未知/不可信"处理）
     */
    fun sha256(file: File, maxBytes: Long = Long.MAX_VALUE): String? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}