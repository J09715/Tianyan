package top.tianyan.app.ui.workflow

/** Keep unsaved history visible until persistence succeeds, without replacing operation errors. */
internal fun workflowHistoryError(operationError: String?, historyErrors: Map<String, String>): String? =
    operationError ?: historyErrors.values.firstOrNull()?.let { "工作流历史保存失败：$it" }
