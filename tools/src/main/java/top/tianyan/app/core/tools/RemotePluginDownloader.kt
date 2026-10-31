package top.tianyan.app.core.tools

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Disk-backed parts survive cancellation. Only hash-verified bytes may enter the package importer. */
class RemotePluginDownloader(private val http: OkHttpClient, private val cache: File) {
    suspend fun download(release: RemotePluginRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(cache.mkdirs() || cache.isDirectory)
        val directory = File(cache, release.sha256).apply { mkdirs() }
        val output = File(directory, "package.txplugin")
        val context = currentCoroutineContext()
        if (valid(output, release.size, release.sha256) { context.ensureActive() }) {
            progress(release.size, release.size)
            return@withContext output
        }
        progress(0, release.size)
        check(!output.exists() || output.delete()) { "无法清理损坏的下载缓存" }
        require(directory.usableSpace >= release.size * 2 + 64L * 1024 * 1024) { "下载空间不足，需预留分片与合并文件空间；安装还需额外空间" }
        var completed = 0L
        release.parts.forEachIndexed { index, part ->
            context.ensureActive()
            val file = File(directory, "part-$index")
            if (!valid(file, part.size, part.sha256) { context.ensureActive() }) {
                check(!file.exists() || file.delete()) { "无法清理损坏的分片" }
                val temporary = File(directory, "part-$index.tmp")
                try {
                    http.newCall(Request.Builder().url(part.url).build()).consumeRemotePluginResponse { response ->
                        require(response.isSuccessful && response.request.url.isHttps) { "插件下载失败：${response.code}" }
                        require(response.code != 206) { "服务器返回了不完整文件" }
                        val body = requireNotNull(response.body)
                        require(body.contentLength() == -1L || body.contentLength() == part.size) { "分片大小不符" }
                        body.byteStream().use { input -> temporary.outputStream().buffered().use { target ->
                            val buffer = ByteArray(128 * 1024)
                            var count = 0L
                            var reported = 0L
                            var reportedAt = System.nanoTime()
                            while (true) {
                                context.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= part.size) { "下载内容超出声明大小" }
                                target.write(buffer, 0, n)
                                val now = System.nanoTime()
                                if (count - reported >= 1024 * 1024 || now - reportedAt >= 100_000_000) {
                                    progress(completed + count, release.size); reported = count; reportedAt = now
                                }
                            }
                        } }
                    }
                    require(valid(temporary, part.size, part.sha256) { context.ensureActive() }) { "分片完整性校验失败" }
                    check(temporary.renameTo(file)) { "无法保存分片" }
                } finally { temporary.delete() }
            }
            completed += part.size
            progress(completed, release.size)
        }
        val staging = File(directory, "package.tmp")
        try {
            staging.outputStream().buffered().use { target ->
                release.parts.indices.forEach { index ->
                    File(directory, "part-$index").inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            target.write(buffer, 0, n)
                        }
                    }
                }
            }
            require(valid(staging, release.size, release.sha256) { context.ensureActive() }) { "完整插件包校验失败" }
            check(staging.renameTo(output))
            release.parts.indices.forEach { File(directory, "part-$it").delete() }
            output
        } finally { staging.delete() }
    }

    companion object {
        internal fun valid(file: File, size: Long, hash: String, check: () -> Unit = {}): Boolean {
            if (!file.isFile || file.length() != size) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { check(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == hash
        }
    }
}
