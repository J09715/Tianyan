package top.tianyan.app.core.tools

import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol
import top.tianyan.app.plugin.packages.SemanticVersion

/** Immutable version directories plus an atomic selection pointer. Import never runs code. */
internal class LocalPluginStore(private val root: File, private val cache: File) {
    private val mutations = Mutex()

    fun load(): List<ToolManifest> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::selected)

    fun contains(id: String, version: String): Boolean = versionDirectory(id, version).resolve("manifest.json").isFile

    fun manifest(id: String, version: String): ToolManifest? = runCatching { readVersion(versionDirectory(id, version)) }.getOrNull()

    fun payload(id: String, version: String? = null): File? =
        (if (version == null) load().find { it.id == id } else readVersion(versionDirectory(id, version)))
            ?.let { versionDirectory(id, it.version).resolve("payload") }

    suspend fun import(
        input: () -> InputStream, total: Long?, expected: ToolManifest?,
        validateCandidate: (ToolManifest) -> Unit,
        progress: (LocalPluginImportProgress) -> Unit,
        maximumBytes: Long = LocalPluginArchive.MAX_BYTES,
        maximumEntries: Int = LocalPluginArchive.MAX_ENTRIES,
    ): ToolManifest = mutations.withLock {
        check(cache.mkdirs() || cache.isDirectory)
        val staging = File(cache, "txplugin-${java.util.UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            val operation = currentCoroutineContext()
            val manifest = input().use { LocalPluginArchive.extract(it, staging, total, progress,
                checkCancellation = { operation.ensureActive() }, maximumBytes = maximumBytes, maximumEntries = maximumEntries) }
            operation.ensureActive()
            require(expected == null || manifest == expected) { "插件清单已变化，请重新预览并确认" }
            validateCandidate(manifest)
            val destination = versionDirectory(manifest.id, manifest.version)
            if (destination.exists()) {
                val existing = readVersion(destination)
                require(existing == manifest) { "同版本插件内容不同，请递增版本号" }
                return@withLock manifest
            }
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            // Stabilize an old implicit selection before a new immutable directory can appear.
            selected(destination.parentFile!!)?.let { old ->
                if (!File(destination.parentFile, "active-version").exists()) writeSelection(destination.parentFile!!, old.version)
            }
            check(staging.renameTo(destination)) { "无法提交插件版本目录" }
            try { writeSelection(destination.parentFile!!, manifest.version) } catch (failure: Exception) {
                check(destination.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                destination.deleteRecursively()
                throw failure
            }
            manifest
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Remove only a confirmed current static package, under the same lock used for imports. */
    suspend fun removeWeb(expected: ToolManifest) = mutations.withLock {
        require(top.tianyan.app.plugin.packages.WebPluginPackagePolicy.isWeb(expected))
        val directory = versionDirectory(expected.id, expected.version).parentFile!!
        require(selected(directory) == expected) { "插件版本已变化，请重新确认移除" }
        val canonical = directory.canonicalFile
        require(canonical.toPath().startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile)
        java.nio.file.Files.walkFileTree(canonical.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                if (failure != null) throw failure
                java.nio.file.Files.delete(directory)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun selected(idDir: File): ToolManifest? {
        val pointer = File(idDir, "active-version")
        if (pointer.exists() || File(idDir, "active-version.bak").exists()) {
            return runCatching {
                val bytes = AtomicFile(pointer).readFully()
                require(bytes.size <= 128)
                val version = bytes.toString(Charsets.UTF_8)
                readVersion(versionDirectory(idDir.name, version))
            }.getOrNull()
        }
        val versions = idDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull(::readVersion)
        val semantic = versions.filter { SemanticVersion.parse(it.version) != null }
        return if (semantic.isNotEmpty()) semantic.maxWithOrNull { a, b ->
            val order = SemanticVersion.parse(a.version)!!.compareTo(SemanticVersion.parse(b.version)!!)
            if (order != 0) order else a.version.compareTo(b.version)
        } else versions.maxByOrNull { it.version }
    }

    private fun readVersion(directory: File): ToolManifest? = runCatching {
        val file = File(directory, "manifest.json")
        require(file.length() in 1..LocalPluginArchive.MAX_MANIFEST)
        val manifest = PluginPackageProtocol.decode(file.readText())
        ToolManifestValidator.validateAll(listOf(manifest))
        require(directory.name == manifest.version && directory.parentFile!!.name == manifest.id)
        manifest
    }.getOrNull()

    private fun versionDirectory(id: String, version: String): File {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{1,63}")) && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")))
        val target = File(root, "$id/$version").canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath()))
        return target
    }

    private fun writeSelection(idDir: File, version: String) {
        val atomic = AtomicFile(File(idDir, "active-version"))
        val stream = atomic.startWrite()
        try { stream.write(version.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        // finishWrite 依赖 renameTo 覆盖旧 base：POSIX（CI/设备）原子替换成功，Windows 上
        // 目标存在即失败——.new 残留而 base 保持旧版本。自愈校验让指针在任何环境都收敛
        // 到本次写入值（设备上 rename 成功时此分支不触发，不破坏 AtomicFile 的原子性）。
        val pointer = File(idDir, "active-version")
        if (pointer.readText(Charsets.UTF_8) != version) {
            File(idDir, "active-version.new").delete()
            pointer.writeText(version, Charsets.UTF_8)
        }
    }
}
