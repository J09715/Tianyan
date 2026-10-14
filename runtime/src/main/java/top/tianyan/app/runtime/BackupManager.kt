package top.tianyan.app.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.common.files.SafeFileTree

/** 单个分区在备份包内的条目数与字节数，用于 UI 展示与恢复前核对。 */
data class BackupSectionStat(
    val section: String,
    val entries: Int,
    val bytes: Long,
)

/** 导出结果：包名 + 各分区统计。 */
data class BackupExportResult(
    val archiveName: String,
    val sections: List<BackupSectionStat>,
    val totalBytes: Long,
)

/** 恢复暂存结果：已解包到待应用目录，等待重启后落盘。 */
data class BackupRestoreStaged(
    val stagedAt: Long,
    val sections: List<String>,
    val totalBytes: Long,
)

/** 待应用恢复的条目清单。 */
data class PendingRestore(
    val sourceDir: File,
    val stagedAt: Long,
    val sections: List<String>,
)

/** 应用结果：区分成功与「无待恢复」，供启动日志与 UI 提示使用。 */
sealed interface PendingRestoreOutcome {
    data class Applied(val sections: List<String>) : PendingRestoreOutcome
    data object None : PendingRestoreOutcome
    data class Failed(val reason: String) : PendingRestoreOutcome
}

/**
 * 天衍全量数据备份。
 *
 * 覆盖四类真源：
 * - `db/`：Room 主库 `tianyan.db`（会话、模型档案、项目索引、红队事实等全部表）
 * - `prefs/`：DataStore 偏好文件（主题、模型端点、工具配置、密钥密文）
 * - `blobs/`：`files/harness_blobs`（大载荷外置沙箱，DB 里只存指针，必须一起带走）
 * - `files/`：attachments 与 workspace 目录（技能包、附件、用户项目）
 *
 * 导出只做读操作 + WAL checkpoint，不阻塞写路径。
 *
 * 恢复不在运行中直接覆盖：Room 与 DataStore 此刻都持有打开的文件句柄，
 * 强改会造成「写进已被 unlink 的旧 inode」这类静默丢数据。恢复因此分两段——
 * 运行时只把 ZIP 解到 `files/tianyan-restore-pending` 并写标记，下次启动在任何
 * DAO/Room 构造之前由 [applyPendingRestore] 落盘。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val pathManager: RuntimePathManager,
) {
    private val backupRoot: File get() = File(context.filesDir, BACKUP_ROOT_DIR).apply { mkdirs() }

    /** 本地自留副本目录（导出到共享存储的同时在这里留一份，便于二次导出）。 */
    fun localBackupDir(): File = backupRoot

    suspend fun latestLocalBackup(): File? = withContext(Dispatchers.IO) {
        backupRoot.listFiles { file -> file.isFile && file.name.endsWith(ARCHIVE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
    }

    suspend fun localBackupSize(): Long = withContext(Dispatchers.IO) {
        latestLocalBackup()?.length() ?: 0L
    }

    /**
     * 把全量数据写入 [output]（调用方负责 URI/流生命周期）。
     * 归档同时写入本地 [backupRoot]，返回的包名即两侧同名文件。
     */
    suspend fun export(output: OutputStream, archiveName: String): AppResult<BackupExportResult> =
        withContext(Dispatchers.IO) {
            try {
                val name = normalizeArchiveName(archiveName)
                val stats = mutableListOf<BackupSectionStat>()
                val localFile = File(backupRoot, name)
                localFile.parentFile?.mkdirs()

                checkpointDatabase()

                val temp = File(backupRoot, "$name.tmp")
                ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                    writeSection(zip, SECTION_DB, listOfNotNull(databaseFile()), stats)
                    writeSection(zip, SECTION_PREFS, listOfNotNull(preferencesFile()), stats)
                    writeSection(zip, SECTION_BLOBS, listOfNotNull(blobDir()), stats)
                    writeSection(zip, SECTION_FILES, listOfNotNull(attachmentsDirIfPresent(), workspaceDirIfPresent()), stats)

                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(buildManifest(name, stats).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                // 目标流由调用方持有（SAF URI 的生命周期），这里只冲刷不关闭。
                temp.inputStream().buffered().use { input ->
                    val sink = output.buffered()
                    input.copyTo(sink)
                    sink.flush()
                }

                if (localFile.exists()) localFile.delete()
                check(temp.renameTo(localFile)) { "无法写入本地备份副本" }
                trimLocalBackups()

                AppResult.Success(
                    BackupExportResult(name, stats.toList(), stats.sumOf { it.bytes }),
                )
            } catch (throwable: Throwable) {
                // 目标流中途失败时目标位置会留半截包；SAF 无法回滚，只能尽力删掉。
                runCatching { output.close() }
                AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "备份失败", throwable))
            }
        }

    /**
     * 解包到待应用目录并写标记。运行中不碰真实数据文件。
     */
    suspend fun stageRestore(input: InputStream): AppResult<BackupRestoreStaged> =
        withContext(Dispatchers.IO) {
            try {
                val pendingRoot = pendingRoot()
                if (pendingRoot.exists()) SafeFileTree.delete(pendingRoot)
                check(pendingRoot.mkdirs()) { "无法创建恢复暂存目录" }

                val sections = mutableSetOf<String>()
                var totalBytes = 0L
                var entries = 0
                try {
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            entries++
                            require(entries <= MAX_ARCHIVE_ENTRIES) { "备份包文件数量过多" }
                            val normalized = entry.name.replace('\\', '/').trimStart('/')
                            require(normalized.isNotBlank()) { "备份包包含空路径" }
                            require(normalized.split('/').none { it == ".." }) {
                                "备份包包含越界路径：${entry.name}"
                            }
                            val target = File(pendingRoot, normalized).canonicalFile
                            require(isInside(pendingRoot, target) && target != pendingRoot) {
                                "备份包包含越界路径：${entry.name}"
                            }
                            if (entry.isDirectory) {
                                check(target.isDirectory || target.mkdirs()) { "无法创建目录：${entry.name}" }
                            } else {
                                check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                                    "无法创建目录：${entry.name}"
                                }
                                target.outputStream().buffered().use { sink ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var written = 0L
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        written += read
                                        totalBytes += read
                                        require(written <= MAX_ARCHIVE_ENTRY_BYTES) {
                                            "备份包单个文件过大：${entry.name}"
                                        }
                                        require(totalBytes <= MAX_ARCHIVE_TOTAL_BYTES) { "备份包解压后体积过大" }
                                        sink.write(buffer, 0, read)
                                    }
                                }
                                sections += normalized.substringBefore('/')
                            }
                            zip.closeEntry()
                        }
                    }
                    require(entries > 0) { "备份包为空" }
                } catch (error: Throwable) {
                    SafeFileTree.delete(pendingRoot)
                    throw error
                }

                File(pendingRoot, MARKER_FILE).writeText(stagedAt().toString())
                AppResult.Success(
                    BackupRestoreStaged(stagedAt(), sections.sorted(), totalBytes),
                )
            } catch (throwable: Throwable) {
                AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "恢复失败", throwable))
            }
        }

    fun hasPendingRestore(): Boolean = markerFile().isFile

    fun pendingRestoreInfo(): PendingRestore? {
        val marker = markerFile()
        if (!marker.isFile) return null
        val root = pendingRoot()
        if (!root.isDirectory) return null
        return PendingRestore(
            sourceDir = root,
            stagedAt = marker.readText().trim().toLongOrNull() ?: 0L,
            sections = root.listFiles().orEmpty().map { it.name }.filter { it != MARKER_FILE }.sorted(),
        )
    }

    fun discardPendingRestore() {
        runCatching {
            val root = pendingRoot()
            if (root.exists()) SafeFileTree.delete(root)
        }
    }

    /**
     * 启动路径调用：在任何 DAO / Room / DataStore 首次触碰真实文件之前把暂存数据落盘。
     * 调用方需保证这是 onCreate 里最早的文件操作。
     */
    fun applyPendingRestore(): PendingRestoreOutcome {
        val pending = pendingRestoreInfo() ?: return PendingRestoreOutcome.None
        return try {
            val applied = mutableListOf<String>()
            val databaseTarget = databaseTarget()
            if (pending.sourceDir.resolve(SECTION_DB).isDirectory) {
                replaceSingleFile(File(pending.sourceDir, SECTION_DB), databaseTarget()) {
                    applied += SECTION_DB
                }
                // 旧库遗留的 WAL/SHM 必须一起清掉：留着会让 SQLite 把恢复前的
                // 事务重放进新库，得到一份既不是旧数据也不是备份数据的混合体。
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    File("${databaseTarget.path}$suffix").takeIf { it.exists() }?.delete()
                }
            }
            if (pending.sourceDir.resolve(SECTION_PREFS).isDirectory) {
                replaceSingleFile(File(pending.sourceDir, SECTION_PREFS), preferencesTarget()) {
                    applied += SECTION_PREFS
                }
            }
            if (pending.sourceDir.resolve(SECTION_BLOBS).isDirectory) {
                replaceTree(File(pending.sourceDir, SECTION_BLOBS), blobTarget()) {
                    applied += SECTION_BLOBS
                }
            }
            if (pending.sourceDir.resolve(SECTION_FILES).isDirectory) {
                val restored = File(pending.sourceDir, SECTION_FILES)
                restored.listFiles().orEmpty().forEach { child ->
                    val name = child.name
                    val target = when (name) {
                        BACKUP_ATTACHMENTS_NAME -> attachmentsDir()
                        BACKUP_WORKSPACE_NAME -> pathManager.workspaceDir
                        else -> null
                    } ?: return@forEach
                    replaceTree(child, target) { applied += "$SECTION_FILES/$name" }
                }
            }
            SafeFileTree.delete(pending.sourceDir)
            PendingRestoreOutcome.Applied(applied)
        } catch (error: Throwable) {
            PendingRestoreOutcome.Failed(error.message ?: "恢复失败")
        }
    }

    // ---------------- 内部实现 ----------------

    /** WAL 落盘：否则导出的 db 可能缺最近一次事务。 */
    private fun checkpointDatabase() {
        runCatching {
            val db = database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
    }

    private fun writeSection(
        zip: ZipOutputStream,
        section: String,
        roots: List<File>,
        stats: MutableList<BackupSectionStat>,
    ) {
        var entries = 0
        var bytes = 0L
        roots.forEach { root ->
            when {
                root.isDirectory -> root.walkTopDown()
                    .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .filter { it != root && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .forEach { file ->
                        val relative = file.toRelativeString(root).replace(File.separatorChar, '/')
                        val entryName = if (file.isDirectory) {
                            "$section/${root.name}/$relative/"
                        } else {
                            "$section/${root.name}/$relative"
                        }
                        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        if (file.isFile) {
                            val size = file.length()
                            file.inputStream().buffered().use { it.copyTo(zip) }
                            entries++
                            bytes += size
                        }
                        zip.closeEntry()
                    }

                root.isFile -> {
                    zip.putNextEntry(ZipEntry("$section/${root.name}").apply { time = root.lastModified() })
                    root.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries++
                    bytes += root.length()
                }
            }
        }
        if (entries > 0) stats += BackupSectionStat(section, entries, bytes)
    }

    private fun buildManifest(archiveName: String, stats: List<BackupSectionStat>): String {
        val json = buildJsonObject {
            put("format", JsonPrimitive(MANIFEST_FORMAT))
            put("package", JsonPrimitive(context.packageName))
            put("appVersion", JsonPrimitive(appVersionName()))
            put("createdAt", JsonPrimitive(stagedAt()))
            put("databaseSchema", JsonPrimitive(SCHEMA_HINT))
            put(
                "sections",
                buildJsonArray {
                    stats.forEach { section ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(section.section))
                                put("entries", JsonPrimitive(section.entries))
                                put("bytes", JsonPrimitive(section.bytes))
                            },
                        )
                    }
                },
            )
        }
        return Json.encodeToString(JsonObject.serializer(), json)
    }

    private fun trimLocalBackups() {
        backupRoot.listFiles { file -> file.isFile && file.name.endsWith(ARCHIVE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOCAL_BACKUPS)
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * 单文件替换：旧文件先改名让位，新文件写入；任何一步失败都把旧文件改回原名。
     * 备份名固定派生自目标名，不能由调用方传入——否则传入目标同名就等于覆盖备份。
     */
    private fun replaceSingleFile(
        stagedDir: File,
        target: File?,
        onApplied: () -> Unit,
    ) {
        val destination = target ?: return
        val source = stagedDir.listFiles()?.firstOrNull { it.isFile } ?: return
        destination.parentFile?.mkdirs()
        val backup = File(destination.parentFile, "${destination.name}$FILE_BACKUP_SUFFIX")
        if (backup.exists()) backup.delete()
        val hadOriginal = destination.exists()
        if (hadOriginal) {
            check(destination.renameTo(backup)) { "无法备份旧文件：${destination.name}" }
        }
        try {
            source.copyTo(destination, overwrite = true)
        } catch (error: Throwable) {
            runCatching { destination.delete() }
            if (hadOriginal) runCatching { backup.renameTo(destination) }
            throw error
        }
        if (hadOriginal) runCatching { backup.delete() }
        onApplied()
    }

    /** 目录替换：旧树移到 `.bak`，新树就位，失败回滚。 */
    private fun replaceTree(staged: File, target: File, onApplied: () -> Unit) {
        if (!staged.exists()) return
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, "${target.name}.restore.bak")
        if (backup.exists()) SafeFileTree.delete(backup)
        if (target.exists()) {
            check(target.renameTo(backup)) { "无法移走旧目录：${target.name}" }
        }
        try {
            if (staged.isDirectory) {
                SafeFileTree.copy(staged, target)
            } else {
                target.parentFile?.mkdirs()
                staged.copyTo(target, overwrite = true)
            }
        } catch (error: Throwable) {
            if (target.exists()) runCatching { SafeFileTree.delete(target) }
            if (backup.exists()) runCatching { backup.renameTo(target) }
            throw error
        }
        runCatching { if (backup.exists()) SafeFileTree.delete(backup) }
        onApplied()
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)

    /** 导出用：只带走真实存在的文件，不凭空造空条目。 */
    private fun databaseFile(): File? = databaseTarget().takeIf { it.isFile }

    private fun preferencesFile(): File? = preferencesTarget().takeIf { it.isFile }

    private fun databaseTarget(): File = context.getDatabasePath(DATABASE_NAME)

    private fun preferencesTarget(): File =
        File(File(context.filesDir, DATASTORE_DIR), PREFERENCES_FILE_NAME)

    private fun blobDir(): File? = blobTarget().takeIf { it.isDirectory }

    private fun blobTarget(): File = File(context.filesDir, BLOB_DIR_NAME)

    private fun attachmentsDirIfPresent(): File? =
        pathManager.attachmentsDir.takeIf { it.isDirectory }

    private fun workspaceDirIfPresent(): File? =
        pathManager.workspaceDir.takeIf { it.isDirectory }

    private fun attachmentsDir(): File = pathManager.attachmentsDir

    private fun pendingRoot(): File = File(context.filesDir, PENDING_DIR_NAME)

    private fun markerFile(): File = File(pendingRoot(), MARKER_FILE)

    private fun normalizeArchiveName(raw: String): String {
        val trimmed = raw.substringAfterLast('/').trim()
        require(trimmed.isNotBlank()) { "备份文件名无效" }
        val base = trimmed.removeSuffix(ARCHIVE_SUFFIX).replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(base.isNotBlank()) { "备份文件名无效" }
        return "$base$ARCHIVE_SUFFIX"
    }

    private fun stagedAt(): Long = System.currentTimeMillis()

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")

    companion object {
        const val SECTION_DB = "db"
        const val SECTION_PREFS = "prefs"
        const val SECTION_BLOBS = "blobs"
        const val SECTION_FILES = "files"
        const val BACKUP_ATTACHMENTS_NAME = "attachments"
        const val BACKUP_WORKSPACE_NAME = "workspace"
        const val MANIFEST_ENTRY = "manifest.json"
        const val MANIFEST_FORMAT = "tianyan-backup/1"

        private const val ARCHIVE_SUFFIX = ".zip"
        private const val BACKUP_ROOT_DIR = "tianyan-backups"
        private const val PENDING_DIR_NAME = "tianyan-restore-pending"
        private const val MARKER_FILE = ".pending"
        private const val DATABASE_NAME = "tianyan.db"
        private const val DATASTORE_DIR = "datastore"
        private const val PREFERENCES_FILE_NAME = "settings.preferences_pb"
        private const val BLOB_DIR_NAME = "harness_blobs"
        private const val MAX_LOCAL_BACKUPS = 5
        private const val FILE_BACKUP_SUFFIX = ".restore.bak"
        private const val MAX_ARCHIVE_ENTRIES = 200_000
        private const val MAX_ARCHIVE_ENTRY_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_TOTAL_BYTES = 32L * 1024L * 1024L * 1024L

        /** 写入 manifest 的 schema 提示：恢复的是本机同版本 App 产出的库，schema 不匹配由 Room 迁移链兜底。 */
        private const val SCHEMA_HINT = "tianyan.db"
    }
}