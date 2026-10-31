package top.tianyan.app.ui.settings.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.StatsDateRange
import top.tianyan.app.core.model.StatsDateRangePreset
import top.tianyan.app.core.model.StatsLedgerGroupDim
import top.tianyan.app.core.model.StatsLedgerScopeDim
import top.tianyan.app.core.model.StatsSnapshot
import java.time.LocalDate

/**
 * 统计界面的状态。
 *
 * 三层控件各占一个字段，互不耦合：
 *  - [scopeDim] / [scopeValue]：作用域，决定数字"属于谁"。改它要**重新查库**，
 *    因为过滤发生在 SQL 侧（按会话过滤尤其必须如此：按日聚合行里没有 sessionId）。
 *  - [groupDim]：分组方式，决定同一批数字"怎么拆"。三种分组已在快照里预计算，
 *    改它只是纯 UI 切换，**不查库**。
 */
data class StatsUiState(
    val range: StatsDateRange = StatsDateRange.allTime(),
    val scopeDim: StatsLedgerScopeDim = StatsLedgerScopeDim.ALL,
    val scopeValue: String? = null,
    val groupDim: StatsLedgerGroupDim = StatsLedgerGroupDim.MODEL,
    val snapshot: StatsSnapshot? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class StatsViewModel(
    private val statsRepository: StatsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadSnapshot(_uiState.value.range, _uiState.value.scopeDim, _uiState.value.scopeValue)
    }

    fun setDateRangePreset(preset: StatsDateRangePreset) {
        val now = LocalDate.now()
        val newRange = when (preset) {
            StatsDateRangePreset.ALL_TIME -> StatsDateRange.allTime()
            StatsDateRangePreset.LAST_30_DAYS -> StatsDateRange.last30Days(now)
            StatsDateRangePreset.PREVIOUS_MONTH -> StatsDateRange.previousMonth(now)
            StatsDateRangePreset.PREVIOUS_QUARTER -> StatsDateRange.previousQuarter(now)
            StatsDateRangePreset.CUSTOM -> _uiState.value.range
        }
        // 换了时间范围，原来选中的取值可能在新区间里根本不存在，一并清掉让它重新落到第一个。
        loadSnapshot(newRange, _uiState.value.scopeDim, null)
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        loadSnapshot(StatsDateRange.custom(start, end), _uiState.value.scopeDim, null)
    }

    /** 作用域维度或取值变化 —— 需要重新查库。 */
    fun setLedgerScope(dim: StatsLedgerScopeDim, value: String?) {
        loadSnapshot(_uiState.value.range, dim, value)
    }

    /** 分组方式切换 —— 预计算已就绪，纯 UI 切换，不查库。 */
    fun setLedgerGroupDim(dim: StatsLedgerGroupDim) {
        _uiState.value = _uiState.value.copy(groupDim = dim)
    }

    fun refresh() {
        loadSnapshot(_uiState.value.range, _uiState.value.scopeDim, _uiState.value.scopeValue)
    }

    private fun loadSnapshot(range: StatsDateRange, scopeDim: StatsLedgerScopeDim, scopeValue: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(range = range, isLoading = true, error = null)
            try {
                val first = statsRepository.buildSnapshot(range, scopeDim, scopeValue)
                // 作用域切到某个维度但还没落到具体取值时，自动取列表里的第一个，
                // 否则会出现「选了按模型却看不到任何模型」的空档。
                // 取值列表恒按未限定口径统计、与作用域无关，所以下面至多再查一次就收敛，
                // 不会出现来回抖动。
                val resolved = resolveScopeValue(first, scopeDim, scopeValue)
                val snapshot = if (resolved == scopeValue) {
                    first
                } else {
                    statsRepository.buildSnapshot(range, scopeDim, resolved)
                }
                _uiState.value = _uiState.value.copy(
                    range = range,
                    scopeDim = scopeDim,
                    scopeValue = resolved,
                    snapshot = snapshot,
                    isLoading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StatsViewModel", "Failed to build stats snapshot: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "统计数据加载失败，请点击重试",
                )
            }
        }
    }

    /** 把作用域取值收敛成列表里确实存在的一个；整体作用域恒为 null。 */
    private fun resolveScopeValue(
        snapshot: StatsSnapshot,
        scopeDim: StatsLedgerScopeDim,
        scopeValue: String?,
    ): String? {
        val options = snapshot.ledger.scopeOptions
        val candidates = when (scopeDim) {
            StatsLedgerScopeDim.ALL -> return null
            StatsLedgerScopeDim.MODEL -> options.models
            StatsLedgerScopeDim.PROVIDER -> options.providers
            StatsLedgerScopeDim.SESSION -> options.sessions
            StatsLedgerScopeDim.BOT -> options.bots
        }
        return scopeValue?.takeIf { value -> candidates.any { it.id == value } }
            ?: candidates.firstOrNull()?.id
    }
}
