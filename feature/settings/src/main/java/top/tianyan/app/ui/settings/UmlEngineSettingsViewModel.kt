package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.runtime.uml.UmlEngine
import top.tianyan.app.runtime.uml.UmlProbe
import top.tianyan.app.runtime.uml.UmlRootfsInstaller

/**
 * 运行引擎设置页的状态机。
 *
 * UML（完整 root Linux：自带内核 + root）是默认执行引擎；本页面只负责：
 *  1. 显示 UML 是否就位——缺 guest 镜像时触发 [UmlRootfsInstaller] 后台下载（带进度）；
 *  2. 跑 UmlProbe 前置探测（参考信息）——判定 BLOCKED 时给「切回 PRoot」的显式出路；
 *  3. 开关是 opt-out：默认 UML，关掉 = 显式切回 PRoot。
 */
@HiltViewModel
class UmlEngineSettingsViewModel @Inject constructor(
    private val preferences: RuntimePreferences,
    private val engine: UmlEngine,
    private val probe: UmlProbe,
    private val rootfsInstaller: UmlRootfsInstaller,
) : ViewModel() {

    /** 页面阶段。public，供屏幕直接消费。 */
    enum class Phase { PROBING, READY, NO_ARTIFACTS, BLOCKED }

    data class Ui(
        val phase: Phase = Phase.PROBING,
        val umlEnabled: Boolean = true,
        val missingBinaries: List<String> = emptyList(),
        val probeResult: UmlProbe.Result? = null,
        val blockReason: String? = null,
        val rootfsInstalled: Boolean = false,
        val rootfsPreparing: Boolean = false,
        val rootfsProgress: Float = 0f,
        val rootfsError: String? = null,
        /** 产物来源（manifest / 内置回退）——用户能看出用的是清单版本还是回退。 */
        val rootfsSource: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.PROBING) }
            val enabled = preferences.umlEngineEnabled.first()
            _ui.update { it.copy(umlEnabled = enabled) }

            // 缺 guest 镜像（默认引擎的最后一块产物）→ 就地后台补齐，不阻断页面
            val missing = engine.missing()
            val missingBinaries = missing.filter { it != top.tianyan.app.runtime.uml.UmlRuntimeLayout.UML_ROOTFS_NAME }
            _ui.update { it.copy(missingBinaries = missingBinaries) }
            if (missingBinaries.isNotEmpty()) {
                // 内核四件套缺失（需 prepare-uml-runtime.sh 预取）→ 整卡降级
                _ui.update { it.copy(phase = Phase.NO_ARTIFACTS) }
                return@launch
            }
            updateRootfsUi()
            if (rootfsInstaller.state != UmlRootfsInstaller.State.READY) {
                rootfsInstaller.ensure(
                    onDone = { updateRootfsUi() },
                    onProgress = { p ->
                        _ui.update { cur -> cur.copy(rootfsPreparing = true, rootfsProgress = p) }
                    },
                )
            }

            // 探测是 12s 级阻塞内核试跑，放到默认线程池，别卡主线程
            val result = withContext(Dispatchers.Default) { probe.run() }
            _ui.update { it.copy(probeResult = result) }
            if (result.ruledOut()) {
                _ui.update { it.copy(phase = Phase.BLOCKED, blockReason = result.detail) }
            } else {
                _ui.update { it.copy(phase = Phase.READY) }
            }
        }
    }

    /** 手动触发的探测重跑（产物补齐后刷新用）。 */
    fun refreshProbe() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { probe.run() }
            _ui.update { it.copy(probeResult = result) }
            if (result.ruledOut()) {
                _ui.update { it.copy(phase = Phase.BLOCKED, blockReason = result.detail) }
            } else if (_ui.value.phase == Phase.PROBING) {
                _ui.update { it.copy(phase = Phase.READY) }
            }
        }
    }

    /** 显式开/关 UML。关 = 切回 PRoot（opt-out）；开且本设备探测通过 → 顺手拉起 guest。 */
    fun onToggleUml(enabled: Boolean) {
        _ui.update { it.copy(umlEnabled = enabled) }
        viewModelScope.launch { preferences.setUmlEngineEnabled(enabled) }
        if (enabled) {
            if (rootfsInstaller.isInstalled()) engine.startAsync()
            else rootfsInstaller.ensure()
        } else {
            engine.stop()
        }
    }

    /** 镜像安装失败/未开始后的手动触发（带进度回推）。 */
    fun retryRootfsInstall() {
        viewModelScope.launch {
            _ui.update { it.copy(rootfsPreparing = true, rootfsError = null) }
            rootfsInstaller.ensure(
                onDone = { updateRootfsUi() },
                onProgress = { p ->
                    _ui.update { cur -> cur.copy(rootfsPreparing = true, rootfsProgress = p) }
                },
            )
        }
    }

    private fun updateRootfsUi() {
        val s = rootfsInstaller
        _ui.update {
            it.copy(
                rootfsInstalled = s.isInstalled(),
                rootfsPreparing = s.state == UmlRootfsInstaller.State.INSTALLING,
                rootfsProgress = s.progress,
                rootfsError = if (s.state == UmlRootfsInstaller.State.FAILED) s.lastError else null,
                rootfsSource = s.artifactSource.ifBlank { null },
            )
        }
    }
}
