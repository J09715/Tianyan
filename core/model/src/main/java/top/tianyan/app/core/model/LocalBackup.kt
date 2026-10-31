package top.tianyan.app.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Logical records, never a copy of a live SQLite database or device-bound ciphertext. */
@Serializable
data class BackupRecords(val tables: Map<String, List<JsonObject>> = emptyMap())

@Serializable
data class BackupAsset(val path: String, val bytes: Long, val sha256: String)

@Serializable
data class LocalBackupManifest(
    val formatVersion: Int = 1,
    val databaseVersion: Int = 54,
    val createdAt: Long,
    val records: BackupRecords,
    val preferences: JsonObject,
    val assets: List<BackupAsset>,
    val sourceAttachments: String,
    val sourceWorkspace: String,
    val sourceFiles: String,
)

data class BackupPreview(
    val counts: Map<String, Int>,
    val skippedRecords: Int,
    val assetCount: Int,
    val assetBytes: Long,
    val preferenceCount: Int,
)
