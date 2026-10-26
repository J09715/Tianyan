package top.tianyan.app.core.tools

import top.tianyan.app.runtime.RuntimePathManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

sealed interface LocalPluginPreparationEvent {
    data class Copying(
        val message: String,
        val bytesCopied: Long,
        val totalBytes: Long,
    ) : LocalPluginPreparationEvent {
        val fraction: Float
            get() = if (totalBytes > 0L) {
                (bytesCopied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            } else {
                1f
            }
    }

    data class Ready(val payloadPath: String?) : LocalPluginPreparationEvent
}

/** Copies an imported plugin payload into the distro-owned /opt/tianyan tree. */
class LocalPluginPayloadManager(
    private val registry: ToolRegistry,
    private val pathManager: RuntimePathManager,
) {
    fun prepare(toolId: String, distroId: String, version: String? = null, integrity: Map<String, String>? = null): Flow<LocalPluginPreparationEvent> = flow {
        val source = registry.localPayloadRoot(toolId, version)?.takeIf { it.isDirectory }
        if (source == null) {
            require(integrity == null) { "v2 插件 payload 不存在" }
            emit(LocalPluginPreparationEvent.Ready(null))
            return@flow
        }
        val importsRoot = File(pathManager.tianyanRootDir(distroId), "imports").apply { mkdirs() }
        val target = File(importsRoot, toolId)
        val canonicalRoot = importsRoot.canonicalFile
        require(target.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) { "非法插件 ID：$toolId" }
        target.deleteRecursively()

        val sourceFiles = source.walkTopDown().onEach {
            require(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "payload 不能包含符号链接" }
        }.filter { it.isFile }.take(LocalPluginArchive.MAX_ENTRIES + 1).toList()
        require(sourceFiles.size < LocalPluginArchive.MAX_ENTRIES)
        require(integrity == null || sourceFiles.map { it.relativeTo(source).invariantSeparatorsPath }.toSet() == integrity.keys) { "payload 文件集合已变化" }
        val totalBytes = sourceFiles.sumOf { it.length() }
        var copiedBytes = 0L
        var nextProgressReport = 0L
        target.mkdirs()

        for (sourceFile in sourceFiles) {
            currentCoroutineContext().ensureActive()
            val relativePath = sourceFile.relativeTo(source).invariantSeparatorsPath
            val targetFile = File(target, relativePath)
            require(sourceFile.canonicalFile.toPath().startsWith(source.canonicalFile.toPath()))
            require(targetFile.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()))
            val digest = MessageDigest.getInstance("SHA-256")
            targetFile.parentFile?.mkdirs()
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read
                        require(copiedBytes <= LocalPluginArchive.MAX_BYTES)
                        if (copiedBytes >= nextProgressReport || copiedBytes == totalBytes) {
                            emit(
                                LocalPluginPreparationEvent.Copying(
                                    message = "[COPY] 正在复制到沙盒目录：$relativePath · " +
                                        "${formatBytes(copiedBytes)}/${formatBytes(totalBytes)}",
                                    bytesCopied = copiedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                            nextProgressReport = copiedBytes + COPY_PROGRESS_INTERVAL_BYTES
                        }
                    }
                }
            }
            require(integrity == null || digest.digest().joinToString("") { "%02x".format(it) } == integrity[relativePath]) { "payload SHA-256 校验失败" }
            targetFile.setReadable(true, false)
            if (integrity == null && targetFile.extension.equals("sh", ignoreCase = true)) {
                normalizeShellScript(targetFile)
            }
            if (targetFile.extension.equals("sh", ignoreCase = true) || "/bin/" in targetFile.invariantSeparatorsPath) {
                targetFile.setExecutable(true, false)
            }
        }
        emit(LocalPluginPreparationEvent.Ready("/opt/tianyan/imports/$toolId"))
    }.flowOn(Dispatchers.IO)

    /** Windows-created ZIPs commonly carry BOM/CRLF and no executable bit. */
    private fun normalizeShellScript(file: File) {
        val original = file.readBytes()
        val withoutBom = if (
            original.size >= 3 &&
            original[0] == 0xEF.toByte() &&
            original[1] == 0xBB.toByte() &&
            original[2] == 0xBF.toByte()
        ) {
            original.copyOfRange(3, original.size)
        } else {
            original
        }
        var text = withoutBom.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (!text.startsWith("#!")) text = "#!/bin/sh\n$text"
        file.writeText(text, Charsets.UTF_8)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val COPY_PROGRESS_INTERVAL_BYTES = 32L * 1024L * 1024L
    }
}
