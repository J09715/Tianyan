package top.tianyan.app.core.tools.backup

import android.util.AtomicFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import top.tianyan.app.core.database.*
import top.tianyan.app.core.datastore.BackupPreferenceStore
import top.tianyan.app.core.model.*
import top.tianyan.app.core.tools.AiProfileTransferFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

class PreparedBackup internal constructor(
    internal val stage: File,
    internal val manifest: LocalBackupManifest,
    internal val expected: BackupRecords,
    internal val expectedPreferences: JsonObject,
    val preview: BackupPreview,
)

/** Serializes exports/restores; a durable journal spans Room, portable preferences, and new files. */
class LocalBackupService(
    private val repository: DatabaseBackupRepository,
    private val preferences: BackupPreferenceStore,
    private val locations: BackupLocations,
) {
    private val mutex = Mutex()
    private val resources = BackupResources(locations)
    private val journalFile get() = File(locations.files, "backup-state/pending.json")

    suspend fun export(): File = mutex.withLock {
        recoverLocked()
        locations.cache.mkdirs()
        val stage = File(locations.cache, "export_${UUID.randomUUID()}").apply { check(mkdir()) }
        val destination = File(locations.cache, "tianyan_backup_${UUID.randomUUID()}.zip")
        try {
            val original = repository.snapshot()
            val prefs = preferences.snapshot()
            val (records, assets) = resources.collect(BackupRecordPolicy.portable(original), File(stage, "assets"))
            val manifest = LocalBackupManifest(createdAt = System.currentTimeMillis(), records = records, preferences = prefs,
                assets = assets, sourceAttachments = locations.attachments.absolutePath, sourceWorkspace = locations.workspace.absolutePath,
                sourceFiles = locations.files.absolutePath)
            preferences.validate(prefs)
            BackupArchive.write(destination, manifest, File(stage, "assets"))
            check(repository.snapshot() == original && preferences.snapshot() == prefs) { "备份期间数据已变化，请重试" }
            currentCoroutineContext().ensureActive()
            destination
        } catch (failure: Exception) { destination.delete(); throw failure
        } finally { stage.deleteRecursively() }
    }

    suspend fun preview(input: InputStream): PreparedBackup = mutex.withLock {
        recoverLocked()
        val (stage, archiveManifest) = BackupArchive.read(input, locations.cache)
        try {
            val manifest = archiveManifest.copy(records = BackupRecordPolicy.upgradeLegacy(archiveManifest.records))
            repository.validate(manifest.records)
            preferences.validate(manifest.preferences)
            validateModels(manifest.records)
            val expected = repository.snapshot()
            val missing = BackupRecordPolicy.missing(BackupRecordPolicy.portable(manifest.records), expected)
            val count = missing.tables.values.sumOf { it.size }
            PreparedBackup(stage, manifest, expected, preferences.snapshot(), BackupPreview(
                counts = missing.tables.mapValues { it.value.size }, skippedRecords = manifest.records.tables.values.sumOf { it.size } - count,
                assetCount = manifest.assets.size, assetBytes = manifest.assets.sumOf { it.bytes },
                preferenceCount = manifest.preferences.values.count { it != JsonNull },
            ))
        } catch (failure: Exception) { stage.deleteRecursively(); throw failure }
    }

    suspend fun restore(prepared: PreparedBackup, restorePreferences: Boolean): Int = mutex.withLock {
        recoverLocked()
        check(prepared.stage.canonicalFile.parentFile == locations.cache.canonicalFile && prepared.stage.isDirectory) { "恢复预览已失效，请重新选择文件" }
        check(repository.snapshot() == prepared.expected && preferences.snapshot() == prepared.expectedPreferences) { "数据已变化，请重新预览" }
        val operation = UUID.randomUUID().toString()
        val rootName = "restored_$operation"
        val root = File(locations.attachments, rootName)
        check(!root.exists())
        val missing = BackupRecordPolicy.missing(BackupRecordPolicy.portable(prepared.manifest.records), prepared.expected)
        val records = resources.materialize(missing, prepared.manifest, root)
        val activeExists = prepared.expected.tables["harness_models"].orEmpty().any { it["isActive"]?.jsonPrimitive?.intOrNull == 1 }
        var activated = activeExists
        val adjusted = records.copy(tables = records.tables.mapValues { (table, rows) -> if (table != "harness_models") rows else rows.map { row ->
            val active = !activated; activated = true
            JsonObject(row + ("isActive" to JsonPrimitive(if (active) 1 else 0)))
        } })
        val applied = if (restorePreferences) prepared.manifest.preferences else prepared.expectedPreferences
        val journal = RestoreJournal(operation, rootName, prepared.expectedPreferences, applied)
        currentCoroutineContext().ensureActive()
        writeJournal(journal)
        try {
            // DB commit and the receipt must finish even when the settings page is closed mid-restore.
            withContext(NonCancellable) {
                repository.merge(prepared.expected, adjusted, operation) {
                    check(root.mkdirs()) { "无法创建恢复目录" }
                    prepared.manifest.assets.forEach { asset ->
                        val source = File(prepared.stage, "assets/${asset.path}")
                        check(source.length() == asset.bytes && BackupArchive.digest(source) == asset.sha256) { "恢复暂存文件校验失败" }
                        val target = File(root, asset.path)
                        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    }
                    resources.rewriteBlobs(root, prepared.manifest)
                    if (restorePreferences) preferences.replace(prepared.expectedPreferences, applied, operation)
                }.also { recoverLocked() }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { recoverLocked() }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        } finally { prepared.stage.deleteRecursively() }
    }

    suspend fun recoverPending() = mutex.withLock { withContext(NonCancellable) { recoverLocked() } }

    fun discard(prepared: PreparedBackup?) {
        if (prepared != null && prepared.stage.canonicalFile.parentFile == locations.cache.canonicalFile) prepared.stage.deleteRecursively()
    }

    private suspend fun recoverLocked() {
        val atomic = AtomicFile(journalFile)
        if (!journalFile.exists() && !File(journalFile.path + ".bak").exists()) return
        val text = atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                check(output.size() + n <= 1024 * 1024) { "恢复日志过大" }; output.write(buffer, 0, n)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val journal = BackupArchive.json.decodeFromString<RestoreJournal>(text)
        check(journal.rootName == "restored_${journal.operationId}" && journal.operationId.matches(Regex("[a-f0-9-]{36}"))) { "恢复日志无效" }
        if (!repository.committed(journal.operationId)) {
            preferences.rollback(journal.operationId, journal.applied, journal.previous)
            val root = File(locations.attachments, journal.rootName)
            check(root.canonicalFile.parentFile == locations.attachments.canonicalFile) { "恢复目录无效" }
            check(!root.exists() || root.deleteRecursively()) { "无法清理未提交的恢复资源" }
        }
        atomic.delete()
        check(!journalFile.exists()) { "无法清理恢复日志" }
    }

    private fun writeJournal(journal: RestoreJournal) {
        check(journalFile.parentFile!!.mkdirs() || journalFile.parentFile!!.isDirectory)
        val atomic = AtomicFile(journalFile)
        val output = atomic.startWrite()
        try {
            output.write(BackupArchive.json.encodeToString(journal).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun validateModels(records: BackupRecords) {
        val profiles = records.tables["harness_models"].orEmpty().map { row -> AiModelProfileExport(
            name = row.getValue("name").jsonPrimitive.content, model = row.getValue("model").jsonPrimitive.content,
            baseUrl = row.getValue("baseUrl").jsonPrimitive.content, temperature = row["temperature"]?.jsonPrimitive?.floatOrNull,
            topP = row["topP"]?.jsonPrimitive?.floatOrNull, maxTokens = row["maxTokens"]?.jsonPrimitive?.intOrNull,
            contextTokens = row["contextTokens"]?.jsonPrimitive?.intOrNull,
            compactionKeepRecentTokens = row["compactionKeepRecentTokens"]?.jsonPrimitive?.intOrNull,
            compactionReserveTokens = row["compactionReserveTokens"]?.jsonPrimitive?.intOrNull,
            requestsPerMinutePerKey = row.getValue("requestsPerMinutePerKey").jsonPrimitive.int,
        ) }
        if (profiles.isNotEmpty()) AiProfileTransferFormat.validate(profiles)
    }

    @Serializable private data class RestoreJournal(
        val operationId: String, val rootName: String, val previous: JsonObject, val applied: JsonObject,
    )
}
