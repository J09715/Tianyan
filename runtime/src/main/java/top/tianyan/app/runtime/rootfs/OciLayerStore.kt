package top.tianyan.app.runtime.rootfs

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

internal class OciLayerStore(private val http: OkHttpClient) {
    /**
     * 下载单个 OCI layer（带断点续传）：
     * - 已校验完成的缓存直接命中（`sha256-<digest>.layer`）；
     * - 中断残留的 `.part` 通过 HTTP `Range: bytes=<已有大小>-` 续传，不必整层重下；
     * - 摘要校验失败或服务器返回 416 时清空重试一次完整下载。
     */
    suspend fun download(
        url: String,
        digest: String,
        token: () -> String,
        cacheDir: File,
        progress: suspend (Long) -> Unit,
    ): File {
        val expected = validateDigest(digest)
        val target = File(cacheDir, "sha256-$expected.layer")
        if (target.isFile) {
            if (sha256(target) == expected) return target
            check(target.delete()) { "无法移除损坏的 OCI layer 缓存" }
        }
        val partial = File(cacheDir, "sha256-$expected.part")
        var attempt = 0
        while (true) {
            attempt++
            val existing = if (partial.isFile) partial.length() else 0L
            val md = MessageDigest.getInstance("SHA-256")
            if (existing > 0) {
                // 断点续传：先累计本地已有部分的摘要，再续传剩余字节
                partial.inputStream().use { input -> hashInto(md, input) }
            }
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .apply { token().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            var append = false
            var count = existing
            var actual: String? = null
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> append = true
                    200 -> {
                        // 服务器忽略 Range：从头重写
                        if (existing > 0) {
                            partial.delete()
                            count = 0
                            md.reset()
                        }
                    }
                    416 -> {
                        // 本地已完整：直接校验后提交
                        actual = if (partial.isFile) sha256(partial) else ""
                    }
                    else -> check(false) { "下载 OCI layer 失败 HTTP ${response.code}" }
                }
                if (actual == null) {
                    response.body.byteStream().use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                md.update(buffer, 0, read)
                                count += read
                                progress(count)
                            }
                        }
                    }
                    actual = md.digest().joinToString("") { "%02x".format(it) }
                }
            }
            if (actual == expected) {
                check(partial.renameTo(target)) { "无法提交 OCI layer 缓存" }
                return target
            }
            // 摘要不符：清空后重试（上限内）
            partial.delete()
            if (attempt >= MAX_BLOB_DOWNLOAD_ATTEMPTS) {
                check(false) { "OCI layer 摘要校验失败：期望 $expected（已重试 $MAX_BLOB_DOWNLOAD_ATTEMPTS 次）" }
            }
        }
    }

    private fun validateDigest(value: String): String {
        val match = Regex("^sha256:([0-9a-fA-F]{64})$").matchEntire(value)
            ?: throw SecurityException("非法或不支持的 OCI digest：$value")
        return match.groupValues[1].lowercase()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> hashInto(md, input) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashInto(md: MessageDigest, input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
    }

    private companion object {
        const val USER_AGENT = "Tianyan/proot-distro-5.10.0"
        const val MAX_BLOB_DOWNLOAD_ATTEMPTS = 2
    }
}
