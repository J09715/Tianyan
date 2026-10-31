package top.tianyan.app.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tianyan.app.core.model.AppUpdateInfo
import java.io.File
import java.io.FileOutputStream

/**
 * 天衍 · 应用版本更新管理器 (GitHub Releases API)
 */
class AppUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    companion object {
        const val GITHUB_REPO = "tianyan-lab/tianyan"
        const val GITHUB_REPO_URL = "https://github.com/tianyan-lab/tianyan"
        const val QQ_GROUP_ID = "964382207"
        private const val RELEASES_API = "https://api.github.com/repos/tianyan-lab/tianyan/releases/latest"
    }

    /**
     * 向 GitHub 请求最新 Release 信息并与当前版本比对
     */
    suspend fun checkUpdate(currentVersionName: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Tianyan-App/${currentVersionName}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub 响应错误 HTTP ${response.code}")
                }
                val body = response.body.string()
                val jsonElement = json.parseToJsonElement(body).jsonObject

                val tagName = jsonElement["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val latestVersion = tagName.removePrefix("v").trim()
                val title = jsonElement["name"]?.jsonPrimitive?.content ?: tagName
                val bodyText = jsonElement["body"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = jsonElement["html_url"]?.jsonPrimitive?.content ?: GITHUB_REPO_URL
                val publishedAt = jsonElement["published_at"]?.jsonPrimitive?.content.orEmpty()

                // 查找 assets 中的 apk 文件
                var apkUrl: String? = null
                var apkSize: Long? = null
                val assets = jsonElement["assets"]?.jsonArray
                if (assets != null) {
                    for (asset in assets) {
                        val assetObj = asset.jsonObject
                        val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                            apkSize = assetObj["size"]?.jsonPrimitive?.longOrNull
                            break
                        }
                    }
                }

                val hasUpdate = VersionCompare.isNewer(latestVersion, currentVersionName)

                AppUpdateInfo(
                    currentVersion = currentVersionName,
                    latestVersion = latestVersion.ifBlank { currentVersionName },
                    hasUpdate = hasUpdate,
                    releaseTitle = title,
                    releaseNotes = bodyText,
                    releaseUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    publishedAt = publishedAt,
                )
            }
        }
    }

    /**
     * 向 GitHub 请求指定版本（tag）的 Release 说明
     */
    suspend fun fetchReleaseNotes(versionName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = versionName.substringBefore('-').trim()
            val tag = if (clean.startsWith("v")) clean else "v$clean"
            val request = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/tags/$tag")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Tianyan-App/$versionName")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub 响应错误 HTTP ${response.code}")
                }
                val body = response.body.string()
                val jsonElement = json.parseToJsonElement(body).jsonObject
                check(jsonElement["tag_name"]?.jsonPrimitive?.contentOrNull == tag) {
                    "更新日志版本与当前版本不匹配"
                }
                jsonElement["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
    }

    /**
     * 下载 APK 文件并报告下载进度
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${response.code}")
            }

            val body = response.body
            val contentLength = body.contentLength().takeIf { it > 0 }
            val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(downloadDir, "tianyan-latest.apk")
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, contentLength)
                    }
                    output.flush()
                }
            }

            apkFile
        }
    }

    /**
     * 调起系统安装器安装 APK
     */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

}
