package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.tools.*
import top.tianyan.app.core.model.PluginLibraryChannel
import top.tianyan.app.core.datastore.PluginLibraryPreferences

data class RemotePluginsState(
    val busy: Boolean = false, val message: String? = null, val error: Boolean = false,
    val progress: Float? = null, val releases: List<RemotePluginRelease> = emptyList(),
    val pendingWeb: WebPluginImportConfirmation? = null,
    val channel: PluginLibraryChannel = PluginLibraryChannel.GITHUB,
    val operationPluginId: String? = null, val downloading: Boolean = false,
    val downloadedBytes: Long = 0, val totalBytes: Long = 0,
)

class RemotePluginsViewModel(
    private val library: RemotePluginLibrary, private val tools: ToolManager,
    private val web: WebPluginRepository,
    private val preferences: PluginLibraryPreferences,
) : ViewModel() {
    private val mutable = MutableStateFlow(RemotePluginsState())
    val state = mutable.asStateFlow()
    val installed = tools.observeTools().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val webPackages = web.packages
    val importing = tools.localPluginImportState
    private var job: Job? = null
    init { run("正在读取插件渠道") {
        mutable.value = mutable.value.copy(channel = preferences.channel())
        loadCatalog()
    } }
    fun refresh() = run("正在检查插件更新") {
        loadCatalog()
    }
    fun selectChannel(channel: PluginLibraryChannel) {
        if (mutable.value.busy || mutable.value.pendingWeb != null || channel == mutable.value.channel) return
        run("正在切换插件渠道") {
            preferences.select(channel)
            mutable.value = mutable.value.copy(channel = channel, releases = emptyList())
            loadCatalog()
        }
    }
    private suspend fun loadCatalog() {
        tools.syncRegistry()
        web.refresh()
        val catalog = library.refresh(mutable.value.channel)
        mutable.value = mutable.value.copy(releases = catalog.latest(), message = "目录已更新 · ${catalog.latest().size} 个插件")
    }
    fun download(release: RemotePluginRelease) = run("正在连接下载…", release.pluginId, release.size) {
        val selected = RemotePluginSource.select(release, mutable.value.channel)
        val uri = library.download(selected) { bytes, total ->
            mutable.value = mutable.value.copy(progress = (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f),
                downloadedBytes = bytes.coerceIn(0, total), totalBytes = total, message = "正在下载")
        }
        mutable.value = mutable.value.copy(downloading = false, progress = null, downloadedBytes = selected.size,
            message = "下载完成，正在验证插件包…")
        if (release.execution == "WEB") {
            val preview = web.preview(uri)
            checkIdentity(release, preview.manifest)
            mutable.value = mutable.value.copy(pendingWeb = WebPluginImportConfirmation(uri, preview), message = "下载完成，请确认导入")
        } else {
            // Inspect once against the signed catalog before reserving the existing native import flow.
            tools.startLocalPluginImport(uri, "${release.name}-${release.version}.txplugin", requireAvailable = true).join()
            val pending = tools.localPluginImportState.value as? LocalPluginImportState.PendingConfirmation
            if (pending != null) {
                try { checkIdentity(release, pending.manifest) }
                catch (failure: Exception) { tools.cancelLocalPluginImport(); throw failure }
            } else if (tools.localPluginImportState.value is LocalPluginImportState.Failed) {
                error((tools.localPluginImportState.value as LocalPluginImportState.Failed).message)
            }
            mutable.value = mutable.value.copy(message = "下载完成")
        }
    }
    private fun checkIdentity(release: RemotePluginRelease, manifest: ToolManifest) {
        require(RemotePluginSource.matches(release, manifest)) { "插件包身份与签名目录不一致" }
    }
    fun confirmProcess() { tools.confirmLocalPluginImport() }
    fun cancelProcess() { tools.cancelLocalPluginImport() }
    fun cancelWeb() { mutable.value = mutable.value.copy(pendingWeb = null) }
    fun confirmWeb(pending: WebPluginImportConfirmation) = run("正在导入 Web 插件", mutable.value.operationPluginId) {
        require(mutable.value.pendingWeb === pending)
        web.import(pending.uri, pending.preview.manifest)
        mutable.value = mutable.value.copy(pendingWeb = null, message = "Web 插件已导入，可在外部 Web 插件页打开")
    }
    fun cancelDownload() { job?.cancel() }
    private fun run(message: String, pluginId: String? = null, totalBytes: Long = 0, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = message, error = false,
            progress = if (totalBytes > 0) 0f else null, operationPluginId = pluginId,
            downloading = totalBytes > 0, downloadedBytes = 0, totalBytes = totalBytes)
        val cancelledMessage = if (totalBytes > 0) "下载已停止，已完成分片可在重试时复用" else "操作已取消"
        job = viewModelScope.launch(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(message = cancelledMessage)
                throw cancelled
            } catch (failure: Exception) {
                if (!currentCoroutineContext().isActive) {
                    mutable.value = mutable.value.copy(message = cancelledMessage)
                    throw CancellationException("插件操作已取消")
                }
                mutable.value = mutable.value.copy(error = true, message = failure.message ?: "插件操作失败")
            }
            finally { mutable.value = mutable.value.copy(busy = false, progress = null, downloading = false) }
        }
    }
}
