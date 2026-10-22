package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.runtime.uml.UmlEngine
import top.tianyan.app.runtime.uml.UmlProbe

/**
 * UML 引擎设置页的状态机。
 *
 * 打开页面先做两件事：
 *  1. 查产物是否齐全（UmlEngine.missing()）—— 缺 → NO_ARTIFACTS；
 *  2. 跑 UmlProbe 前置探测 —— 判定 BLOCKED 就不让用户开开关（点了也起不来）。
 * 开关状态从 RuntimePreferences 读，toggle 同时落盘。
 */
@HiltViewModel
class UmlEngineSettingsViewModel @Inject constructor(
    private val preferences: RuntimePreferences,
    private val engine: UmlEngine,
    private val probe: UmlProbe,
) : ViewModel() {

    /** 页面阶段。public，供屏幕直接消费。 */
    enum class Phase { PROBING, READY, NO_ARTIFACTS, BLOCKED }

    data class Ui(
        val phase: Phase = Phase.PROBING,
        val umlEnabled: Boolean = false,
        val missingBinaries: List<String> = emptyList(),
        val probeResult: UmlProbe.Result? = null,
        val blockReason: String? = null,
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

            val missing = engine.missing()
            if (missing.isNotEmpty()) {
                _ui.update { it.copy(phase = Phase.NO_ARTIFACTS, missingBinaries = missing) }
                return@launch
            }
            _ui.update { it.copy(missingBinaries = emptyList()) }

            val result = probe.run()
            _ui.update { it.copy(probeResult = result) }
            if (result.ruledOut()) {
                _ui.update { it.copy(phase = Phase.BLOCKED, blockReason = result.detail) }
            } else {
                _ui.update { it.copy(phase = Phase.READY) }
            }
        }
    }

    /** 重新探测（产物已补齐后手动刷新）。 */
    fun refreshProbe() {
        viewModelScope.launch {
            val result = probe.run()
            _ui.update { it.copy(probeResult = result) }
            if (result.ruledOut()) {
                _ui.update { it.copy(phase = Phase.BLOCKED, blockReason = result.detail) }
            } else if (_ui.value.phase == Phase.PROBING) {
                _ui.update { it.copy(phase = Phase.READY) }
            }
        }
    }

    fun onToggleUml(enabled: Boolean) {
        _ui.update { it.copy(umlEnabled = enabled) }
        viewModelScope.launch { preferences.setUmlEngineEnabled(enabled) }
        // 开启且探测通过时顺手拉起 guest；关闭则停。
        if (enabled && engine.isInstalled() && (probe.run().ruledOut() == false)) {
            engine.startAsync()
        } else if (!enabled) {
            engine.stop()
        }
    }
}
