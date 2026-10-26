package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 天衍 · 版本更新信息模型 (GitHub Releases API)
 */
@Serializable
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseTitle: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String? = null,
    val apkSizeBytes: Long? = null,
    val publishedAt: String = "",
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Success(val info: AppUpdateInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}
