package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

enum class DoctorStatus {
    HEALTHY,
    WARNING,
    ERROR,
    /**
     * 沙箱探测不可达（超时/异常/沙箱正忙）。
     * 探测不到结果 ≠ 配置异常：显示灰牌「暂时无法确认」，不计入待修复项，
     * 避免把「沙箱正忙导致的探测超时」误报成 WARNING/ERROR。
     */
    UNKNOWN,
    CHECKING,
}

enum class DoctorCategory(val displayName: String) {
    SANDBOX("沙箱与存储"),
    NETWORK_SSL("网络与安全"),
    PACKAGE_MANAGER("包管理与源"),
    DEV_RUNTIMES("核心开发环境"),
}

@Serializable
data class DoctorItem(
    val id: String,
    val category: DoctorCategory,
    val title: String,
    val status: DoctorStatus,
    val summary: String,
    val detail: String? = null,
    val fixable: Boolean = true,
)

@Serializable
data class DoctorReport(
    val items: List<DoctorItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val overallStatus: DoctorStatus = DoctorStatus.HEALTHY,
    val healthyCount: Int = 0,
    val warningCount: Int = 0,
    val errorCount: Int = 0,
    /** 探测不可达（沙箱正忙）的条目数；不参与 needsFix 判定。 */
    val unknownCount: Int = 0,
) {
    val isAllHealthy: Boolean get() = overallStatus == DoctorStatus.HEALTHY && items.isNotEmpty()
    val needsFix: Boolean get() = warningCount > 0 || errorCount > 0
}

@Serializable
data class RepairProgress(
    val stepTitle: String,
    val stepIndex: Int,
    val totalSteps: Int = 5,
    val progress: Float = 0.0f,
    val logs: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
)
