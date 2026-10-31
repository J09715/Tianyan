package top.tianyan.app.harness.bot.archive

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotManifest
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.bot.memory.BotMemoryManager

/**
 * Bot 归档：`bot.json` + `memory/`（含 Git 历史）打包为 ZIP。
 *
 * 导入按不可信输入处理：限制包大小、条目数与解压总量，拒绝绝对路径与 `..` 越界。
 */
class BotPackageService(
    private val botRepository: PersonaBotRepository,
    private val orchestrator: BotOrchestrator,
    private val memoryManager: BotMemoryManager,
    private val json: Json,
    private val logger: AppLogger,
) {
    suspend fun exportBot(botId: String, destination: File): File {
        val bot = requireNotNull(botRepository.getBot(botId)) { "Bot 不存在" }
        val staging = File(destination.parentFile ?: File("."), "bot-export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val memoryDir = File(staging, MEMORY_DIR)
            runCatching { memoryManager.exportMemoryTree(botId, memoryDir) }
                .onFailure { logger.w("[BotPackageService] 导出记忆失败: ${it.message}", it) }
            val manifest = BotManifest(
                name = bot.name,
                role = bot.role,
                systemPrompt = bot.systemPrompt,
                modelId = bot.modelId,
                modelVariant = bot.modelVariant,
                allowModelSelection = bot.allowModelSelection,
                avatarSeed = bot.avatarSeed,
                colorHex = bot.colorHex,
            )
            File(staging, MANIFEST).writeText(
                json.encodeToString(BotManifest.serializer(), manifest),
                Charsets.UTF_8,
            )
            destination.parentFile?.mkdirs()
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(File(staging, MANIFEST).readBytes())
                zip.closeEntry()
                if (memoryDir.isDirectory) {
                    memoryDir.walkTopDown().filter { it != memoryDir }.forEach { entry ->
                        val relative = entry.relativeTo(memoryDir).invariantSeparatorsPath
                        if (relative == ".git/index.lock" || relative.endsWith("/index.lock")) return@forEach
                        val name = "$MEMORY_DIR/$relative"
                        if (entry.isDirectory) {
                            zip.putNextEntry(ZipEntry("$name/"))
                            zip.closeEntry()
                        } else if (!Files.isSymbolicLink(entry.toPath())) {
                            zip.putNextEntry(ZipEntry(name))
                            entry.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            logger.i("[BotPackageService] 已导出 Bot ${bot.name} → ${destination.name}")
            return destination
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun importBot(packageFile: File, groupId: String? = null): Bot {
        require(packageFile.isFile) { "安装包不存在" }
        require(packageFile.length() in 1..MAX_PACKAGE_BYTES) { "安装包大小超出限制" }
        val staging = File(packageFile.parentFile ?: File("."), "bot-import-${UUID.randomUUID()}").apply { mkdirs() }
        var createdBotId: String? = null
        try {
            extractSafely(packageFile, staging)
            val manifestFile = File(staging, MANIFEST)
            require(manifestFile.isFile) { "安装包缺少 bot.json" }
            val manifest = runCatching {
                json.decodeFromString(BotManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
            }.getOrElse { throw IOException("bot.json 解析失败: ${it.message}") }
            require(manifest.version == 1) { "不支持的安装包版本: ${manifest.version}" }
            require(manifest.name.isNotBlank() && manifest.name.length <= 40) { "Bot 名称无效" }
            require(manifest.role.isNotBlank() && manifest.role.length <= 40) { "Bot 职责无效" }
            require(manifest.systemPrompt.isNotBlank()) { "Bot 人格设定为空" }

            val bot = orchestrator.createBot(
                name = manifest.name,
                role = manifest.role,
                systemPrompt = manifest.systemPrompt,
                modelId = manifest.modelId,
                modelVariant = manifest.modelVariant,
                allowModelSelection = manifest.allowModelSelection,
                colorHex = manifest.colorHex,
                groupId = groupId,
                avatarSeed = manifest.avatarSeed,
            )
            createdBotId = bot.id
            val memorySource = File(staging, MEMORY_DIR)
            if (memorySource.isDirectory) {
                memoryManager.replaceMemoryTree(bot.id, memorySource, bot.name)
            }
            logger.i("[BotPackageService] 已导入 Bot ${bot.name}")
            return bot
        } catch (failure: Throwable) {
            createdBotId?.let { runCatching { orchestrator.deleteBot(it) } }
            throw failure
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractSafely(packageFile: File, destination: File) {
        val root = destination.canonicalFile.toPath()
        var entries = 0
        var totalBytes = 0L
        ZipFile(packageFile).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                require(++entries <= MAX_ENTRIES) { "安装包条目过多" }
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank()) { "安装包含空条目名" }
                require(!name.startsWith("/") && !name.split('/').any { it == ".." }) { "安装包含越界路径: $name" }
                val target = File(destination, name)
                require(target.canonicalFile.toPath().startsWith(root)) { "安装包路径越界: $name" }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "安装包解压后体积过大" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MANIFEST = "bot.json"
        const val MEMORY_DIR = "memory"
        const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 192L * 1024 * 1024
        const val MAX_ENTRIES = 5_000
        const val COPY_BUFFER = 32 * 1024
    }
}
