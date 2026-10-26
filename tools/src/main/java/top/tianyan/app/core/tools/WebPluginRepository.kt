package top.tianyan.app.core.tools

import android.net.Uri
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

/** Web packages share immutable storage/identity checks with process packages, but never Linux installation. */
class WebPluginRepository(private val registry: ToolRegistry) {
    private val operations = Mutex()
    private val mutablePackages = MutableStateFlow<List<ToolManifest>>(emptyList())
    val packages = mutablePackages.asStateFlow()
    private val changing = MutableStateFlow<Set<String>>(emptySet())

    suspend fun refresh() = withContext(Dispatchers.IO) { operations.withLock { reload() } }
    suspend fun preview(uri: Uri): LocalPluginPreview = when (val result = registry.inspectLocal(uri, execution = "WEB")) {
        is AppResult.Success -> result.data
        is AppResult.Failure -> throwImportFailure(result)
    }

    suspend fun import(uri: Uri, expected: ToolManifest) = withContext(Dispatchers.IO) {
        operations.withLock {
            changing.value = setOf(expected.id)
            try {
                when (val result = registry.importLocal(uri, expected = expected, execution = "WEB")) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> throwImportFailure(result)
                }
            } finally {
                reload()
                changing.value = emptySet()
            }
        }
    }

    suspend fun remove(expected: ToolManifest) = withContext(Dispatchers.IO) {
        operations.withLock {
            changing.value = setOf(expected.id)
            try { registry.removeWebPackage(expected) }
            finally { reload(); changing.value = emptySet() }
        }
    }

    fun isCurrent(expected: ToolManifest): Boolean = expected.id !in changing.value && packages.value.any { it == expected }

    /** Rehash a bounded immutable snapshot on every open; never hand a file path to WebView. */
    suspend fun snapshot(expected: ToolManifest): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        operations.withLock {
            require(isCurrent(expected)) { "Web plugin changed" }
            require(registry.webPackages().any { it == expected }) { "Web plugin selection changed" }
            WebPluginPackagePolicy.validate(expected)
            require(registry.localManifest(expected.id, expected.version) == expected) { "Web plugin manifest changed" }
            val root = requireNotNull(registry.localPayloadRoot(expected.id, expected.version)).toPath()
            require(!Files.isSymbolicLink(root) && Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val expectedHashes = expected.plugin!!.integrity
            val files = linkedMapOf<String, ByteArray>()
            var total = 0L
            var visited = 0
            Files.walk(root, 17).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val path = iterator.next()
                    require(++visited <= WebPluginPackagePolicy.MAX_TREE_ENTRIES && !Files.isSymbolicLink(path)) { "Web plugin tree invalid" }
                    if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                    require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    val relative = root.relativize(path).joinToString("/")
                    val hash = requireNotNull(expectedHashes[relative]) { "Unexpected Web asset" }
                    val remaining = WebPluginPackagePolicy.MAX_PAYLOAD_BYTES - total
                    val bytes = Files.newInputStream(path).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= remaining) { "Web plugin resource limit" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    total += bytes.size
                    require(total <= WebPluginPackagePolicy.MAX_PAYLOAD_BYTES)
                    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hash) { "Web asset integrity failed" }
                    files[relative] = bytes
                }
            }
            require(files.keys == expectedHashes.keys && isCurrent(expected)) { "Web plugin changed" }
            files.toMap()
        }
    }

    private fun reload() { mutablePackages.value = registry.webPackages().sortedBy { it.name } }

    private fun throwImportFailure(result: AppResult.Failure): Nothing {
        val cause = result.error.cause
        if (cause is top.tianyan.app.runtime.service.ProotWebBootstrapLimitException) throw cause
        error("Web plugin processing failed")
    }
}
