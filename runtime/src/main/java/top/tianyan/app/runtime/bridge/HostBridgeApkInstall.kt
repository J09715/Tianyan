package top.tianyan.app.runtime.bridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.bridge.HostBridgeHttp.HttpResponse
import top.tianyan.app.runtime.bridge.adb.EmbeddedAdbManager
import java.io.File

/**
 * Resolves a guest-named APK path and installs it through exactly the same host channels the guest
 * endpoint uses: wireless ADB silent install first, otherwise the system installer via FileProvider.
 * Shared by the guest endpoint and the plugin gateway, kept out of [HostBridge] to stay under the
 * file-size ratchet.
 */
internal class HostBridgeApkInstall(
    private val context: Context,
    private val logger: AppLogger,
    private val embeddedAdbManager: EmbeddedAdbManager,
    private val pathManager: RuntimePathManager,
) {
    suspend fun installAt(apkPath: String): HttpResponse {
        // 将沙箱路径映射到宿主路径
        val hostPath = resolveSandboxPath(apkPath)
        var apkFile = File(hostPath)
        if (!apkFile.isFile) {
            // 智能容错：如果在指定路径未找到，尝试在工作区目录中按文件名模糊搜索匹配的最新 APK
            val candidateName = apkFile.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
                ?: apkPath.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) }
            val fallback = if (candidateName != null && pathManager.workspaceDir.isDirectory) {
                pathManager.workspaceDir.walkTopDown().maxDepth(8)
                    .filter { it.isFile && it.name.equals(candidateName, ignoreCase = true) }
                    .maxByOrNull { it.lastModified() }
            } else null

            if (fallback != null) {
                logger.i("HostBridge: APK 在 '$hostPath' 未找到，通过工作区扫描智能匹配到 '${fallback.absolutePath}'")
                apkFile = fallback
            } else {
                return HttpResponse(404, errorJson("APK file not found: $apkPath (resolved: $hostPath)"))
            }
        }
        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) {
            return HttpResponse(400, errorJson("File does not have .apk extension: ${apkFile.name}"))
        }

        // 优先通过内置无线 ADB 静默安装（无需点击系统弹窗确认）
        val adbState = embeddedAdbManager.state.value
        if (adbState is EmbeddedAdbManager.ConnectionState.Connected) {
            val installResult = embeddedAdbManager.installApk(apkFile)
            if (installResult.isSuccess) {
                logger.i("HostBridge: APK installed via wireless ADB for $apkPath")
                return HttpResponse(200, buildJsonObject {
                    put("success", true)
                    put("message", "APK 已通过无线 ADB 静默安装成功")
                    put("package", apkFile.name)
                    put("channel", "wireless-adb")
                }.toString())
            }
        }

        // 复制到 cache 目录（FileProvider 需要 cache-path 下的文件）
        val installDir = File(context.cacheDir, "bridge-installs").apply { mkdirs() }
        val cachedApk = File(installDir, "tianyan-bridge-${System.currentTimeMillis()}.apk")
        try {
            apkFile.copyTo(cachedApk, overwrite = true)
        } catch (e: Exception) {
            return HttpResponse(500, errorJson("Failed to copy APK: ${e.message}"))
        }

        // 通过 FileProvider + Intent 调起系统安装器
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cachedApk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("APK", uri)
            }
            context.startActivity(intent)
            logger.i("HostBridge: APK install triggered for $apkPath")
            return HttpResponse(200, buildJsonObject {
                put("success", true)
                put("message", "安装请求已发送，请在系统弹窗中确认安装")
                put("package", apkFile.name)
                put("channel", "system-intent")
            }.toString())
        } catch (e: Exception) {
            cachedApk.delete()
            return HttpResponse(500, errorJson("Failed to launch installer: ${e.message}"))
        }
    }

    /**
     * 将沙箱内路径映射到宿主路径。
     * /sdcard/Download/app.apk → /storage/emulated/0/Download/app.apk
     * /workspace/... → pathManager.workspaceDir/...
     * /attachments/... → pathManager.attachmentsDir/...
     */
    private fun resolveSandboxPath(sandboxPath: String): String {
        val trimmed = sandboxPath.trim()
        return when {
            trimmed.startsWith("/sdcard/") ->
                "/storage/emulated/0/${trimmed.removePrefix("/sdcard/")}"
            trimmed == "/sdcard" ->
                "/storage/emulated/0"
            trimmed.startsWith("/storage/emulated/0/") ->
                trimmed // 已经是宿主存储路径
            trimmed.startsWith("/workspace/") ->
                File(pathManager.workspaceDir, trimmed.removePrefix("/workspace/")).absolutePath
            trimmed == "/workspace" ->
                pathManager.workspaceDir.absolutePath
            trimmed.startsWith("/attachments/") ->
                File(pathManager.attachmentsDir, trimmed.removePrefix("/attachments/")).absolutePath
            trimmed == "/attachments" ->
                pathManager.attachmentsDir.absolutePath
            else -> {
                // 若为相对路径或去除了开头的斜杠，先在工作区内检查是否存在
                val wsRelative = File(pathManager.workspaceDir, trimmed.removePrefix("/"))
                if (wsRelative.exists()) {
                    wsRelative.absolutePath
                } else {
                    trimmed // 原样返回（可能是宿主绝对路径）
                }
            }
        }
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString()
}
