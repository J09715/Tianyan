package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 云端版本信息——配置键 `tianyan.latest_version` 的值结构（JSON 字符串，客户端二次解析）。
 * versionCode 用于与 GitHub Releases 检查结果取「更新者」；forceUpdate=true 时强制更新。
 */
@Serializable
data class CloudLatestVersion(
    val versionName: String = "",
    val versionCode: Int = 0,
    val apkUrl: String = "",
    val changelog: String = "",
    val forceUpdate: Boolean = false,
)

