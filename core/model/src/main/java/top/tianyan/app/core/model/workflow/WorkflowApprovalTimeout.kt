package top.tianyan.app.core.model.workflow

/** Missing means unlimited. Explicit invalid values must never become unlimited. */
object WorkflowApprovalTimeout {
    const val CONFIG_KEY = "timeoutSeconds"

    fun validationError(raw: String?): String? =
        if (raw == null || raw.toLongOrNull()?.let { it in 1L..3600L } == true) null
        else "审批等待上限必须为 1–3600 秒的整数；不限时请移除 timeoutSeconds 配置"

    fun milliseconds(raw: String?): Long? {
        require(validationError(raw) == null) { validationError(raw).orEmpty() }
        return raw?.toLong()?.times(1000L)
    }
}
