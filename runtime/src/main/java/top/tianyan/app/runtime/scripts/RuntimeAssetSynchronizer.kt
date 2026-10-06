package top.tianyan.app.runtime.scripts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import top.tianyan.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 🛠️ 天衍资产脚本同步器 (Runtime Asset Synchronizer)
 * 将 APK 内置 assets/scripts/ 下的标准化 Shell 脚本与 tools 自动提取并同步到
 * Linux 沙箱隔离目录 (/opt/tianyan/scripts/ 与 /opt/tianyan/tools/)，并赋予执行权限。
 */
@Singleton
class RuntimeAssetSynchronizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
) {
    suspend fun syncWorkshopScript(distroId: String, fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val safeDistro = distroId.lowercase().trim()
        pathManager.ensureDistroDirectories(safeDistro)
        val target = File(pathManager.tianyanScriptsDir(safeDistro), fileName)
        target.parentFile?.mkdirs()
        target.writeText(content.removePrefix("\uFEFF").replace("\r\n", "\n"), Charsets.UTF_8)
        target.setExecutable(true, false)
        "/opt/tianyan/scripts/$fileName"
    }
    /**
     * 同步指定发行版沙箱内的脚本与资产工具
     */
    suspend fun syncAssetsToDistro(distroId: String) = withContext(Dispatchers.IO) {
        val safeDistro = distroId.lowercase().trim()
        pathManager.ensureDistroDirectories(safeDistro)

        val scriptsTargetDir = pathManager.tianyanScriptsDir(safeDistro)
        val toolsTargetDir = pathManager.tianyanToolsDir(safeDistro)
        val binTargetDir = pathManager.tianyanBinDir(safeDistro)

        // 1. 同步 assets/scripts/ -> /opt/tianyan/scripts/
        syncAssetFolder("scripts", scriptsTargetDir)

        // 2. 同步 assets/tools/ -> /opt/tianyan/tools/
        syncAssetFolder("tools", toolsTargetDir)

        // 3. /opt/tianyan/bin 位于终端与 Agent PATH 首位，包含脚本与 ELF 原生工具。
        syncAssetExecutableFolder("bin", binTargetDir)

        // 4. 同步 assets/certs/ -> /opt/tianyan/certs/ 与 /etc/ssl/certs/java/cacerts
        val certsTargetDir = File(pathManager.tianyanRootDir(safeDistro), "certs")
        syncAssetBinaryFolder("certs", certsTargetDir)

        // 5. RTK 的遥测、历史与原始输出落盘默认关闭；配置只作用于 Agent 包装命令。
        syncAssetTree("rtk", File(pathManager.tianyanRootDir(safeDistro), "data/rtk/config"))

        // 6. Android/Flutter project templates live outside a distro so that
        // creating a workspace does not depend on which distro is active.
        syncAssetTree("templates", File(pathManager.baseDir, "templates"))

        // 7. 精准将标准 cacerts 注入沙箱 OpenJDK 与 系统证书路径
        val builtinCacerts = File(certsTargetDir, "cacerts")
        if (builtinCacerts.exists() && builtinCacerts.length() > 0) {
            val rootfsRoot = pathManager.rootfsDir(safeDistro)
            val targetCacertsLocations: List<File> = listOf(
                File(rootfsRoot, "etc/ssl/certs/java/cacerts"),
                File(rootfsRoot, "usr/lib/jvm/java-17-openjdk-arm64/lib/security/cacerts"),
                File(rootfsRoot, "usr/lib/jvm/default-java/lib/security/cacerts"),
            )
            for (dest in targetCacertsLocations) {
                runCatching {
                    dest.parentFile?.mkdirs()
                    builtinCacerts.copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                }
            }
        }
    }

    private fun syncAssetBinaryFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setReadable(true, false)
            }
        }
    }

    /** 二进制安全地同步可执行 asset，不能经 UTF-8 文本路径读取。 */
    private fun syncAssetExecutableFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                targetFile.setExecutable(true, false)
                targetFile.setReadable(true, false)
            }
        }
    }

    private fun syncAssetFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                val content = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                // 彻底剥离 UTF-8 BOM (\uFEFF) 并规范换行符为 Unix LF
                val cleanContent = content.removePrefix("\uFEFF").replace("\r\n", "\n")
                targetFile.writeText(cleanContent, Charsets.UTF_8)
                targetFile.setExecutable(true, false)
                targetFile.setReadable(true, false)
            }
        }
    }

    private fun syncAssetTree(assetPath: String, targetDir: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) return
        children.forEach { child ->
            val childAsset = "$assetPath/$child"
            val childTarget = File(targetDir, child)
            val nested = runCatching { context.assets.list(childAsset) }.getOrNull().orEmpty()
            if (nested.isEmpty()) {
                runCatching {
                    childTarget.parentFile?.mkdirs()
                    context.assets.open(childAsset).use { input ->
                        childTarget.outputStream().use { output -> input.copyTo(output) }
                    }
                    childTarget.setReadable(true, false)
                }
            } else {
                syncAssetTree(childAsset, childTarget)
            }
        }
    }
}
