package top.tianyan.app.core.tools.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.BackupRecordPolicy
import top.tianyan.app.core.database.DatabaseBackupRepository
import top.tianyan.app.core.datastore.BackupPreferenceStore
import top.tianyan.app.core.model.*
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LegacyBackupRestoreTest {
    @Test fun `old ZIP previews and restores with an unbound history marker`() = runBlocking {
        val temp = Files.createTempDirectory("legacy-backup").toFile()
        var prepared: PreparedBackup? = null
        val locations = BackupLocations(File(temp, "files"), File(temp, "files/attachments"),
            File(temp, "files/workspace"), File(temp, "cache"))
        val repository = MemoryRepository()
        val service = LocalBackupService(repository, EmptyPreferences, locations)
        try {
            val records = repository.records.copy(tables = repository.records.tables + ("harness_sessions" to listOf(
                buildJsonObject { put("id", "old-session"); put("title", "旧会话"); put("approvalMode", "assisted") }
            )))
            val archive = File(temp, "legacy.zip")
            BackupArchive.write(archive, LocalBackupManifest(createdAt = 1L, records = records,
                preferences = JsonObject(emptyMap()), assets = emptyList(), sourceFiles = locations.files.absolutePath,
                sourceAttachments = locations.attachments.absolutePath, sourceWorkspace = locations.workspace.absolutePath),
                File(temp, "assets"))
            val preview = archive.inputStream().use { service.preview(it) }
            prepared = preview
            assertEquals(1, preview.preview.counts["harness_sessions"])
            assertEquals(1, service.restore(preview, restorePreferences = false))
            val restored = repository.records.tables.getValue("harness_sessions").single()
            assertEquals("restore-required", restored.getValue("executionTargetJson").jsonPrimitive.content)
            assertEquals("旧会话", restored.getValue("title").jsonPrimitive.content)
            assertNotNull(repository.committedOperation)
            assertFalse(File(locations.files, "backup-state/pending.json").exists())
        } finally {
            service.discard(prepared)
            temp.deleteRecursively()
        }
    }

    private class MemoryRepository : DatabaseBackupRepository {
        var records = BackupRecords(BackupRecordPolicy.tables.associateWith { emptyList<JsonObject>() })
        var committedOperation: String? = null
        override suspend fun snapshot() = records
        override suspend fun validate(records: BackupRecords) {
            records.tables.getValue("harness_sessions").forEach {
                // The real Room validator requires the current schema before preview can continue.
                require("executionTargetJson" in it) { "备份字段与当前版本不兼容" }
            }
        }
        override suspend fun merge(expected: BackupRecords, records: BackupRecords, operationId: String,
            prepare: suspend () -> Unit): Int {
            check(this.records == expected)
            validate(records)
            prepare()
            this.records = records
            committedOperation = operationId
            return records.tables.values.sumOf { it.size }
        }
        override suspend fun committed(operationId: String) = committedOperation == operationId
    }

    private object EmptyPreferences : BackupPreferenceStore {
        override suspend fun snapshot() = JsonObject(emptyMap())
        override fun validate(values: JsonObject) = Unit
        override suspend fun replace(expected: JsonObject, values: JsonObject, operationId: String) = Unit
        override suspend fun rollback(operationId: String, applied: JsonObject, previous: JsonObject) = Unit
    }
}
