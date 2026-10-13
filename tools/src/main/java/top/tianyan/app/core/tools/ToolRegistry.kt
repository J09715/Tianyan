package top.tianyan.app.core.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.model.ToolRegistryDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class SignedRegistryRequest(
    val manifestUrl: String,
    val signatureUrl: String,
    /** Base64 encoded Ed25519 SubjectPublicKeyInfo. */
    val publicKeyBase64: String,
)

data class LocalPluginImportProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
    val currentEntry: String?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesRead.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

/** Metadata read from a local package before any files are committed. */
data class LocalPluginPreview(
    val manifest: ToolManifest,
    val archiveSizeBytes: Long?,
    val alreadyImported: Boolean,
)

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
}

@Singleton
class ToolRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val persistedFile: File
        get() = File(context.filesDir, "registry/tools.json")

    fun load(): List<ToolManifest> {
        val persisted = persistedFile.takeIf { it.isFile }?.let { file ->
            runCatching { parseAndValidate(file.readText()) }.getOrNull()
        }
        val base = persisted ?: parseAndValidate(
            context.assets.open(REGISTRY_ASSET).bufferedReader().use { it.readText() },
        )
        val locals = loadLocalManifests()
        return (base.filter { remote -> locals.none { it.id == remote.id } } + locals)
    }

    suspend fun updateSigned(request: SignedRegistryRequest): AppResult<Int> = withContext(Dispatchers.IO) {
        try {
            require(request.manifestUrl.startsWith("https://", ignoreCase = true)) { "工具清单必须使用 HTTPS" }
            require(request.signatureUrl.startsWith("https://", ignoreCase = true)) { "签名地址必须使用 HTTPS" }

            val publicKeyBase64 = effectivePublicKey(request)
            // 信任锚说明：官方内置清单（assets/registry/tools.json）在构建期随 APK 分发、运行时只读，
            // 不依赖本下载流程。远程更新使用用户自填公钥时，只能检测传输损坏与内容一致性，
            // 无法防篡改——能控制清单地址的人同样能提供公钥。真正防篡改需要启用下方内置固定公钥。
            val trustMode =
                if (PINNED_REGISTRY_KEY_BASE64.isNotBlank()) "pinned" else "user-supplied"
            logger.w(
                "Tool registry update: verifying with $trustMode public key — " +
                    "integrity-only trust, NOT end-to-end tamper protection.",
            )

            val manifestBytes = download(request.manifestUrl)
            val signatureBytes = decodeSignature(download(request.signatureUrl))
            verifySignature(manifestBytes, signatureBytes, publicKeyBase64)
            val manifests = parseAndValidate(manifestBytes.toString(Charsets.UTF_8))
            val parent = persistedFile.parentFile ?: error("无法创建 Registry 目录")
            check(parent.exists() || parent.mkdirs()) { "无法创建 Registry 目录" }
            val staging = File(parent, "tools.json.part")
            staging.writeBytes(manifestBytes)
            commitPersistedRegistry(staging)
            AppResult.Success(manifests.size)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = if (throwable is SecurityException) ErrorCode.SECURITY else ErrorCode.NETWORK,
                    message = "工具清单更新失败：${throwable.message ?: "未知错误"}",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * 选择验签公钥：内置固定锚点未启用（为空）时使用调用方提供的公钥；
     * 一旦 [PINNED_REGISTRY_KEY_BASE64] 被填入正式公钥，则强制使用它，
     * 忽略外部传入的密钥（防止“控制清单地址的人同时换掉公钥”）。
     */
    private fun effectivePublicKey(request: SignedRegistryRequest): String {
        if (PINNED_REGISTRY_KEY_BASE64.isNotBlank()) {
            return PINNED_REGISTRY_KEY_BASE64
        }
        require(request.publicKeyBase64.isNotBlank()) { "未配置签名公钥" }
        return request.publicKeyBase64
    }

    fun clearRemoteRegistry() {
        persistedFile.delete()
    }

    /** Import a self-contained .txplugin ZIP into app-private storage. */
    suspend fun inspectLocal(uri: Uri): AppResult<LocalPluginPreview> = withContext(Dispatchers.IO) {
        try {
            val totalBytes = queryDocumentSize(uri)
            // Most document providers expose a seekable descriptor. Reading the ZIP central
            // directory avoids walking through multi-gigabyte payload entries before showing
            // the confirmation dialog. Pipe-backed providers fall back to streaming safely.
            val manifestText = inspectSeekableManifest(uri) ?: inspectStreamingManifest(uri)
            val parsed = json.decodeFromString<ToolManifest>(manifestText)
            val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
            ToolManifestValidator.validateAll(listOf(manifest))
            AppResult.Success(
                LocalPluginPreview(
                    manifest = manifest,
                    archiveSizeBytes = totalBytes,
                    alreadyImported = isLocalVersionImported(manifest.id, manifest.version),
                ),
            )
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "本地插件预览失败：${throwable.message ?: "未知错误"}", throwable))
        }
    }

    private fun inspectSeekableManifest(uri: Uri): String? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            val archive = try {
                ZipFile(File("/proc/self/fd/${descriptor.fd}"))
            } catch (_: IOException) {
                return null
            }
            archive.use { zip ->
                var manifestEntry: java.util.zip.ZipEntry? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = validatePluginEntryName(entry.name)
                    if (name == "manifest.json" && !entry.isDirectory) {
                        require(manifestEntry == null) { "插件包包含重复的 manifest.json" }
                        manifestEntry = entry
                    }
                }
                val entry = manifestEntry ?: error("插件包缺少 manifest.json")
                require(entry.size < 0L || entry.size <= MAX_MANIFEST_BYTES) { "manifest.json 过大" }
                return zip.getInputStream(entry).use(::readManifestText)
            }
        }
    }

    private fun inspectStreamingManifest(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = validatePluginEntryName(entry.name)
                    if (name == "manifest.json" && !entry.isDirectory) {
                        return readManifestText(zip)
                    }
                }
            }
        } ?: error("无法读取插件包")
        error("插件包缺少 manifest.json")
    }

    private fun validatePluginEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith('/') && !name.split('/').contains("..")) {
            "插件包包含不安全路径：$name"
        }
        require(name == "manifest.json" || name.startsWith("payload/")) {
            "插件包只允许 manifest.json 与 payload/：$name"
        }
        return name
    }

    private fun readManifestText(input: InputStream): String {
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_MANIFEST_BYTES) { "manifest.json 过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    suspend fun importLocal(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> = withContext(Dispatchers.IO) {
        runCatching {
            context.cacheDir.listFiles()?.filter { it.name.startsWith("txplugin-") || it.name.startsWith("plugin-import-") }
                ?.forEach { it.deleteRecursively() }
        }
        val staging = File(context.cacheDir, "txplugin-${System.nanoTime()}").apply { mkdirs() }
        var committed = false
        try {
            var manifestText: String? = null
            var bytes = 0L
            val totalBytes = queryDocumentSize(uri)
            onProgress(LocalPluginImportProgress(0L, totalBytes, null))
            context.contentResolver.openInputStream(uri)?.use { input ->
                val countingInput = CountingInputStream(input)
                var lastReportedBytes = 0L
                var currentEntry: String? = null
                fun reportProgress(force: Boolean = false) {
                    val read = countingInput.bytesRead
                    if (force || read - lastReportedBytes >= PROGRESS_REPORT_BYTES) {
                        lastReportedBytes = read
                        onProgress(LocalPluginImportProgress(read, totalBytes, currentEntry))
                    }
                }
                ZipInputStream(BufferedInputStream(countingInput)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        currentEntry = name
                        reportProgress(force = true)
                        require(name.isNotBlank() && !name.startsWith('/') && !name.split('/').contains("..")) {
                            "插件包包含不安全路径：$name"
                        }
                        require(name == "manifest.json" || name.startsWith("payload/")) {
                            "插件包只允许 manifest.json 与 payload/：$name"
                        }
                        if (entry.isDirectory) continue
                        val target = File(staging, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                bytes += read
                                require(bytes <= MAX_PACKAGE_BYTES) { "插件包超过大小限制" }
                                output.write(buffer, 0, read)
                                reportProgress()
                            }
                        }
                        if (name == "manifest.json") manifestText = target.readText()
                    }
                }
                reportProgress(force = true)
            } ?: error("无法读取插件包")
            onProgress(LocalPluginImportProgress(totalBytes ?: bytes, totalBytes, "正在校验 manifest.json"))
            val parsed = json.decodeFromString<ToolManifest>(manifestText ?: error("插件包缺少 manifest.json"))
            val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
            ToolManifestValidator.validateAll(listOf(manifest))
            onProgress(LocalPluginImportProgress(totalBytes ?: bytes, totalBytes, "正在保存插件"))
            val versionDir = File(localRoot, "${manifest.id}/${manifest.version}")
            versionDir.parentFile?.mkdirs()
            // 同版本覆盖导入：离线包作者经常在不 bump 版本号的情况下重新打包
            // （修复安装脚本等）。旧逻辑直接拒绝，导致用户被“已导入，无需重复
            // 导入”挡死、修复后的包永远进不来。payload 在每次安装时才复制进
            // 沙箱（LocalPluginPayloadManager.prepare），覆盖这里的存储不会
            // 影响已安装实例；下次安装自动使用新 payload。
            if (versionDir.exists()) {
                val legacy = File(versionDir.parentFile, ".legacy-${manifest.version}-${System.nanoTime()}")
                check(versionDir.renameTo(legacy)) { "无法替换已导入的同版本插件" }
                if (!staging.renameTo(versionDir)) {
                    legacy.renameTo(versionDir)
                    error("无法提交本地插件包")
                }
                legacy.deleteRecursively()
            } else {
                check(staging.renameTo(versionDir)) { "无法提交本地插件包" }
            }
            committed = true
            AppResult.Success(manifest)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "本地插件导入失败：${throwable.message ?: "未知错误"}", throwable))
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    private fun queryDocumentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    fun localPayloadRoot(toolId: String): File? = loadLocalManifests()
        .firstOrNull { it.id == toolId }
        ?.let { File(localRoot, "${it.id}/${it.version}/payload") }

    /**
     * 把 APK 内自带的插件包落盘到 [localRoot]，使「插件与工具中心」无需手动导入即可安装。
     *
     * 为什么需要：`assets/plugins/` 里的离线包此前只是**打包源**——CI 用它生成
     * `.txplugin` 分发，用户得自己拿到 zip 再从工具中心选文件导入。把同一个包同时
     * 放进 APK 的 `assets/bundled-plugins/`，这里在启动时解出来，它就走上了与手动
     * 导入完全相同的通路（同一份 manifest 校验、同一个 localRoot 布局），因此
     * `loadLocalManifests()` 会把它并进 registry，工具中心直接显示为可安装。
     *
     * 幂等与覆盖规则与 [importLocal] 保持一致：包内版本已存在时替换（离线包作者常
     * 在不 bump 版本号的情况下重打包修脚本），版本目录做原子换名，失败则回滚。
     *
     * 与手动导入的差别只有一处：不写 `manifest.json` 之外的任何额外标记，也不改
     * 用户已装实例——payload 在安装时才由 LocalPluginPayloadManager 复制进沙箱。
     *
     * @return 本次实际落盘（或覆盖）的插件 id；无内置包时为空。
     */
    suspend fun seedBundledPlugins(): List<String> = withContext(Dispatchers.IO) {
        val bundled = runCatching { context.assets.list(BUNDLED_PLUGINS_ASSET)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (bundled.isEmpty()) return@withContext emptyList()

        val seeded = mutableListOf<String>()
        for (fileName in bundled.sorted()) {
            if (!fileName.endsWith(".txplugin")) continue
            val staging = File(context.cacheDir, "bundled-plugin-${System.nanoTime()}").apply { mkdirs() }
            var committed = false
            try {
                var manifestText: String? = null
                context.assets.open("$BUNDLED_PLUGINS_ASSET/$fileName").use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name.replace('\\', '/')
                            // 与 importLocal 同一套路径约束：只收 manifest.json 与 payload/，
                            // 且禁止绝对路径与 .. —— 内置包同样是不可信输入，不能因为是
                            // 自带的就放宽校验。
                            require(name.isNotBlank() && !name.startsWith('/') && !name.split('/').contains("..")) {
                                "内置插件包包含不安全路径：$name"
                            }
                            require(name == "manifest.json" || name.startsWith("payload/")) {
                                "内置插件包只允许 manifest.json 与 payload/：$name"
                            }
                            if (entry.isDirectory) continue
                            val target = File(staging, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output -> zip.copyTo(output) }
                            if (name == "manifest.json") manifestText = target.readText()
                        }
                    }
                }
                val parsed = json.decodeFromString<ToolManifest>(
                    manifestText ?: error("内置插件包缺少 manifest.json：$fileName"),
                )
                val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
                ToolManifestValidator.validateAll(listOf(manifest))

                val versionDir = File(localRoot, "${manifest.id}/${manifest.version}")
                versionDir.parentFile?.mkdirs()
                if (versionDir.exists()) {
                    // 已导入同版本：比对 payload 是否一致，一致就跳过，避免每次启动
                    // 都做一次全量目录替换（这个包 5MB，冷启动路径上不该反复搬）。
                    if (staging.contentEquivalentTo(versionDir)) {
                        seeded += manifest.id
                        continue
                    }
                    val legacy = File(versionDir.parentFile, ".legacy-${manifest.version}-${System.nanoTime()}")
                    check(versionDir.renameTo(legacy)) { "无法替换已内置的插件：${manifest.id}" }
                    if (!staging.renameTo(versionDir)) {
                        legacy.renameTo(versionDir)
                        error("无法提交内置插件包：${manifest.id}")
                    }
                    legacy.deleteRecursively()
                } else {
                    check(staging.renameTo(versionDir)) { "无法提交内置插件包：${manifest.id}" }
                }
                committed = true
                seeded += manifest.id
                logger.i("已内置插件：${manifest.id} ${manifest.version}")
            } catch (throwable: Throwable) {
                // 单个内置包失败不影响其余包，也不影响应用启动：内置资源损坏是
                // 打包事故，用户仍可手动导入正确的包来修复。
                logger.w("内置插件落盘失败 $fileName：${throwable.message}", throwable)
            } finally {
                if (!committed) staging.deleteRecursively()
            }
        }
        seeded
    }

    /** 目录内容是否等价（文件名 + 大小 + 内容）。用于跳过重复的内置包搬运。 */
    private fun File.contentEquivalentTo(other: File): Boolean {
        val mine = walkTopDown().filter { it.isFile }.toList()
        val theirs = other.walkTopDown().filter { it.isFile }.toList()
        if (mine.size != theirs.size) return false
        val theirsByPath = theirs.associateBy { it.relativeTo(other).invariantSeparatorsPath }
        return mine.all { file ->
            val relative = file.relativeTo(this).invariantSeparatorsPath
            val counterpart = theirsByPath[relative] ?: return@all false
            file.length() == counterpart.length() && file.readBytes().contentEquals(counterpart.readBytes())
        }
    }

    private fun isLocalVersionImported(toolId: String, version: String): Boolean =
        File(localRoot, "$toolId/$version/manifest.json").isFile

    private fun loadLocalManifests(): List<ToolManifest> {
        if (!localRoot.isDirectory) return emptyList()
        return localRoot.listFiles().orEmpty().mapNotNull { idDir ->
            idDir.listFiles().orEmpty().filter { it.isDirectory }.maxByOrNull { it.name }
                ?.let { versionDir ->
                    runCatching {
                        json.decodeFromString<ToolManifest>(File(versionDir, "manifest.json").readText())
                            .copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
                    }.getOrNull()
                }
        }.let { runCatching { ToolManifestValidator.validateAll(it) }.getOrDefault(emptyList()) }
    }

    private fun download(url: String): ByteArray {
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.request.url.isHttps) {
                throw RegistrySecurityException("工具清单请求被重定向到非 HTTPS 地址")
            }
            check(it.isSuccessful) { "HTTP ${it.code}" }
            val body = it.body
            check(body.contentLength() <= MAX_REGISTRY_BYTES) { "工具清单超过大小限制" }
            val output = ByteArrayOutputStream(minOf(body.contentLength().coerceAtLeast(0), MAX_REGISTRY_BYTES).toInt())
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_REGISTRY_BYTES) {
                        throw IllegalStateException("工具清单超过大小限制")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun decodeSignature(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).trim()
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrElse { bytes }
    }

    private fun verifySignature(
        payload: ByteArray,
        signatureBytes: ByteArray,
        publicKeyBase64: String,
    ) {
        val publicKey = runCatching {
            KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
        }.getOrElse { throw RegistrySecurityException("工具清单公钥无效", it) }
        Signature.getInstance("Ed25519").apply {
            initVerify(publicKey)
            update(payload)
            if (!verify(signatureBytes)) throw RegistrySecurityException("工具清单签名校验失败")
        }
    }

    private class RegistrySecurityException(
        message: String,
        cause: Throwable? = null,
    ) : SecurityException(message, cause)

    private fun parseAndValidate(text: String): List<ToolManifest> {
        val manifests = runCatching {
            json.decodeFromString<List<ToolManifest>>(text)
        }.getOrElse {
            val document = json.decodeFromString<ToolRegistryDocument>(text)
            require(document.schemaVersion == 1) { "不支持的 Registry Schema：${document.schemaVersion}" }
            require(document.version > 0) { "Registry 版本必须为正数" }
            document.tools
        }
        return ToolManifestValidator.validateAll(manifests)
    }

    private fun commitPersistedRegistry(staging: File) {
        runCatching {
            Files.move(
                staging.toPath(),
                persistedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                staging.toPath(),
                persistedFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse { throw IllegalStateException("无法提交工具清单", it) }
    }

    private companion object {
        const val REGISTRY_ASSET = "registry/tools.json"

        /**
         * APK 内自带插件包目录（`.txplugin` 原样存放）。
         *
         * 与 `assets/plugins/` 的区别：后者是**打包源**，只参与 CI 生成分发用的 zip，
         * 不进 APK；这里的包随 APK 分发，启动时由 [seedBundledPlugins] 落到 localRoot，
         * 使用户在「插件与工具中心」无需手动导入即可安装。
         */
        const val BUNDLED_PLUGINS_ASSET = "bundled-plugins"

        const val MAX_REGISTRY_BYTES = 1024 * 1024L
        // Android + ARM64 NDK + Flutter archives can exceed 4 GiB after extraction.
        const val MAX_PACKAGE_BYTES = 8L * 1024L * 1024L * 1024L
        const val PROGRESS_REPORT_BYTES = 4L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 1024L * 1024L

        /**
         * 可选的内置固定公钥（APK 内信任锚）：
         * - 留空 = 当前接受“自定义公钥仅防传输损坏”的语义，[effectivePublicKey] 使用调用方传入的公钥；
         * - 填入项目正式 Ed25519 SPKI（Base64）后，所有远程清单更新强制用该密钥校验，
         *   外部传入的公钥被忽略，能防“控制清单地址者同时提供公钥”的篡改场景。
         */
        const val PINNED_REGISTRY_KEY_BASE64 = ""
    }

    private val localRoot: File
        get() = File(context.filesDir, "plugins")
}
