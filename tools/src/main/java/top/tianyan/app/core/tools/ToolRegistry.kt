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
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy
import top.tianyan.app.plugin.packages.SemanticVersion
import java.io.ByteArrayOutputStream
import java.io.File


import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

class ToolRegistry(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val persistedFile: File
        get() = File(context.filesDir, "registry/tools.json")

    fun load(): List<ToolManifest> = loadAll().filterNot(WebPluginPackagePolicy::isWeb)

    fun webPackages(): List<ToolManifest> = loadLocalManifests().filter(WebPluginPackagePolicy::isWeb)

    private fun loadAll(): List<ToolManifest> {
        val persisted = persistedFile.takeIf { it.isFile }?.let { file ->
            runCatching { parseAndValidate(file.readText()) }.getOrNull()
        }
        val base = (persisted ?: parseAndValidate(
            context.assets.open(REGISTRY_ASSET).bufferedReader().use { it.readText() },
        )).filterNot { it.id in REMOVED_TOOL_IDS }
        val locals = loadLocalManifests().filterNot { it.id in REMOVED_TOOL_IDS }
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
    suspend fun inspectLocal(uri: Uri, execution: String = "PROCESS"): AppResult<LocalPluginPreview> = withContext(Dispatchers.IO) {
        try {
            val totalBytes = queryDocumentSize(uri)
            // Most document providers expose a seekable descriptor. Reading the ZIP central
            // directory avoids walking through multi-gigabyte payload entries before showing
            // the confirmation dialog. Pipe-backed providers fall back to streaming safely.
            val manifestText = inspectSeekableManifest(uri, execution) ?: inspectStreamingManifest(uri, execution)
            val parsed = top.tianyan.app.plugin.packages.PluginPackageProtocol.decode(manifestText)
            val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
            ToolManifestValidator.validateAll(listOf(manifest))
            requireExecution(manifest, execution)
            validateCandidate(manifest)
            if (ProotWebPackagePreflight.required(manifest)) {
                val operation = currentCoroutineContext()
                ProotWebPackagePreflight.preview(context.cacheDir, manifest,
                    { context.contentResolver.openInputStream(uri) ?: error("无法读取插件包") }, { operation.ensureActive() })
            }
            AppResult.Success(
                LocalPluginPreview(
                    manifest = manifest,
                    archiveSizeBytes = totalBytes,
                    alreadyImported = isLocalVersionImported(manifest.id, manifest.version),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "本地插件预览失败：${throwable.message ?: "未知错误"}", throwable))
        }
    }

    private suspend fun inspectSeekableManifest(uri: Uri, execution: String): String? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            val archive = try {
                ZipFile(File("/proc/self/fd/${descriptor.fd}"))
            } catch (_: IOException) {
                return null
            }
            archive.use { zip ->
                var manifestEntry: ZipEntry? = null
                var entryCount = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    require(++entryCount <= if (execution == "WEB") WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES else LocalPluginArchive.MAX_ENTRIES) { "插件包条目过多" }
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

    private suspend fun inspectStreamingManifest(uri: Uri, execution: String): String {
        val operation = currentCoroutineContext()
        return context.contentResolver.openInputStream(uri)?.use { LocalPluginArchive.inspect(it,
            checkCancellation = { operation.ensureActive() },
            maximumBytes = if (execution == "WEB") WebPluginPackagePolicy.MAX_ARCHIVE_BYTES else LocalPluginArchive.MAX_BYTES,
            maximumEntries = if (execution == "WEB") WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES else LocalPluginArchive.MAX_ENTRIES) } ?: error("无法读取插件包")
    }

    private fun validatePluginEntryName(rawName: String) = LocalPluginArchive.entryName(rawName)
    private fun readManifestText(input: InputStream) = LocalPluginArchive.readManifest(input)

    suspend fun importLocal(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
        expected: ToolManifest? = null,
        execution: String = "PROCESS",
    ): AppResult<ToolManifest> = withContext(Dispatchers.IO) {
        try {
            AppResult.Success(localStore.import(
                input = { context.contentResolver.openInputStream(uri) ?: error("无法读取插件包") },
                total = queryDocumentSize(uri), expected = expected,
                validateCandidate = { requireExecution(it, execution); validateCandidate(it) }, progress = onProgress,
                maximumBytes = if (execution == "WEB") WebPluginPackagePolicy.MAX_ARCHIVE_BYTES else LocalPluginArchive.MAX_BYTES,
                maximumEntries = if (execution == "WEB") WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES else LocalPluginArchive.MAX_ENTRIES,
            ))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "本地插件导入失败：${failure.message.orEmpty()}", failure))
        }
    }

    suspend fun removeWebPackage(expected: ToolManifest) = withContext(Dispatchers.IO) { localStore.removeWeb(expected) }

    private fun requireExecution(manifest: ToolManifest, execution: String) {
        require(execution in setOf("PROCESS", "WEB"))
        require((if (WebPluginPackagePolicy.isWeb(manifest)) "WEB" else "PROCESS") == execution) {
            "请从对应的 Web 插件或进程工具入口导入此包"
        }
    }

    private fun validateCandidate(candidate: ToolManifest) {
        val previous = loadAll()
        if (WebPluginPackagePolicy.isWeb(candidate)) require(candidate.id !in REMOVED_TOOL_IDS) { "此工具标识不能用于 Web 插件" }
        val sameTool = previous.find { it.id == candidate.id }
        if (candidate.schemaVersion == 2) {
            require(sameTool?.source != "REMOTE") { "v2 包不能覆盖内置或在线工具身份" }
            require(sameTool?.plugin?.id?.let { it == candidate.plugin!!.id } != false) { "更新不能更换插件身份" }
            require(sameTool == null || WebPluginPackagePolicy.isWeb(sameTool) == WebPluginPackagePolicy.isWeb(candidate)) { "更新不能更换执行形态" }
            if (WebPluginPackagePolicy.isWeb(candidate) && sameTool != null) {
                require(candidate.version == sameTool.version || SemanticVersion.parse(candidate.version)!! > SemanticVersion.parse(sameTool.version)!!) { "Web 插件版本必须递增" }
            }
        } else require(sameTool?.plugin == null) { "不能用 v1 覆盖 v2 插件身份" }
        require(previous.none { it.id != candidate.id && top.tianyan.app.plugin.packages.PluginPackageProtocol.identity(it) == top.tianyan.app.plugin.packages.PluginPackageProtocol.identity(candidate) }) {
            "插件身份已被其他包占用"
        }
    }

    private fun queryDocumentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    private val localStore by lazy { LocalPluginStore(localRoot, context.cacheDir) }
    fun localPayloadRoot(toolId: String, version: String? = null): File? = localStore.payload(toolId, version)
    fun localManifest(toolId: String, version: String): ToolManifest? = localStore.manifest(toolId, version)
    private fun isLocalVersionImported(toolId: String, version: String) = localStore.contains(toolId, version)
    private fun loadLocalManifests(): List<ToolManifest> = localStore.load()

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
        val valid = runCatching {
            PluginSignatureVerifier.verify(payload, signatureBytes, Base64.decode(publicKeyBase64, Base64.DEFAULT))
        }.getOrElse { throw RegistrySecurityException("工具清单公钥或签名格式无效", it) }
        if (!valid) throw RegistrySecurityException("工具清单签名校验失败")
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

    companion object {
        const val REGISTRY_ASSET = "registry/tools.json"
        const val MAX_REGISTRY_BYTES = 1024 * 1024L
        val REMOVED_TOOL_IDS = setOf("claude-code", "openclaw", "hermes-agent")
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
