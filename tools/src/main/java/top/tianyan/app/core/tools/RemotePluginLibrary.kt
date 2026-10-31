package top.tianyan.app.core.tools

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tianyan.app.core.model.PluginLibraryChannel

class RemotePluginLibrary(private val context: Context, private val http: OkHttpClient) {
    private val operations = Mutex()
    private val downloader = RemotePluginDownloader(http.newBuilder().followSslRedirects(false).connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build(), File(context.cacheDir, "remote-plugins"))
    suspend fun refresh(channel: PluginLibraryChannel = PluginLibraryChannel.GITHUB): RemotePluginCatalog = withContext(Dispatchers.IO) {
        operations.withLock {
            val base = RemotePluginSource.base(channel)
            val fileName = RemotePluginSource.catalogFile(channel)
            val bytes = fetch("$base/$fileName", 1024 * 1024)
            val signature = fetch("$base/$fileName.sig", 1024)
            val key = context.assets.open("registry/library-public-key.txt").bufferedReader().use { it.readText().trim() }
            val catalog = RemotePluginCatalog.verify(bytes, signature, Base64.getDecoder().decode(key))
            val revision = File(context.filesDir, "registry/library-revision-${channel.name.lowercase()}")
            require(catalog.revision >= (revision.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0)) { "当前渠道目录较旧，请稍后刷新或切换渠道" }
            revision.parentFile!!.mkdirs()
            val staging = File(revision.parentFile, "${revision.name}.tmp").apply { writeText(catalog.revision.toString()) }
            java.nio.file.Files.move(staging.toPath(), revision.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            catalog
        }
    }
    suspend fun download(release: RemotePluginRelease, progress: (Long, Long) -> Unit): Uri =
        withContext(Dispatchers.IO) { operations.withLock {
            val file = downloader.download(release, progress)
            val coroutine = currentCoroutineContext()
            val manifest = file.inputStream().use { input ->
                top.tianyan.app.plugin.packages.PluginPackageProtocol.decode(LocalPluginArchive.inspect(input, { coroutine.ensureActive() }))
            }
            require(RemotePluginSource.matches(release, manifest)) { "插件包身份与签名目录不一致" }
            Uri.fromFile(file)
        } }

    private suspend fun fetch(url: String, maximum: Int): ByteArray {
        val coroutine = currentCoroutineContext()
        return http.newCall(Request.Builder().url(url).build()).consumeRemotePluginResponse { response ->
            require(response.isSuccessful && response.request.url.isHttps) { "获取插件目录失败：${response.code}" }
            requireNotNull(response.body).byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutine.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= maximum) { "插件目录响应过大" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        }
    }
}
