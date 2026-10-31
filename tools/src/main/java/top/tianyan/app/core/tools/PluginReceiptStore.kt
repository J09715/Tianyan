package top.tianyan.app.core.tools

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.PluginInstallReceipt
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol

/** Version-addressed metadata: DB installedVersion chooses the receipt, never the active package. */
class PluginReceiptStore(private val root: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "plugin-receipts"))

    fun read(distro: String, toolId: String, version: String): PluginInstallReceipt? {
        val file = file(distro, toolId, version)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        require(file.length() <= 256 * 1024 && File(file.path + ".bak").length() <= 256 * 1024) { "插件安装收据过大" }
        val bytes = AtomicFile(file).readFully()
        require(bytes.size <= 256 * 1024) { "插件安装收据过大" }
        val receipt = Json.decodeFromString<PluginInstallReceipt>(bytes.toString(Charsets.UTF_8))
        require(receipt.schemaVersion == 1 && receipt.toolId == toolId && receipt.version == version) { "插件安装收据身份不匹配" }
        return receipt
    }

    fun record(distro: String, manifest: ToolManifest, version: String = manifest.version) {
        val receipt = PluginPackageProtocol.receipt(manifest).copy(version = version, packageVersion = manifest.version)
        write(distro, receipt)
    }

    /** Keep the old key valid until the DB switches; preserve the original grants and resources. */
    internal fun bindToPackageVersion(distro: String, receipt: PluginInstallReceipt) {
        require(receipt.pluginId.startsWith("legacy.tool-"))
        write(distro, receipt.copy(version = requireNotNull(receipt.packageVersion)))
    }

    private fun write(distro: String, receipt: PluginInstallReceipt) {
        val destination = file(distro, receipt.toolId, receipt.version)
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(PluginInstallReceipt.serializer(), receipt).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    private fun file(distro: String, toolId: String, version: String): File {
        require(distro.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) && toolId.matches(Regex("[a-z0-9][a-z0-9-]{1,63}")))
        require(version.isNotBlank() && version.length <= 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(version.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "$distro/$toolId/$hash.json").also {
            require(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        }
    }
}
