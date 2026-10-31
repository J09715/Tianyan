package top.tianyan.app.core.model.workflow

object VirtualScreenWorkflowValidation {
    fun issues(node: WorkflowNode): List<WorkflowValidationIssue> = buildList {
        val action = node.config["action"]
        val def = VirtualScreenWorkflowActions.all.firstOrNull { it.id == action } ?: return@buildList
        fun number(key: String, range: LongRange) {
            if (def.fields.none { it.key == key }) return
            val raw = node.config[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (raw.contains("\${")) return // Runtime interpolation is validated again before injection.
            val value = raw.toLongOrNull()
            if (value == null || value !in range) add(WorkflowValidationIssue(
                "nodes.${node.id}.$key", "$key 必须为 ${range.first}–${range.last} 的整数",
            ))
        }
        def.fields.filter { it.key in setOf("x", "y", "x1", "y1", "x2", "y2") }.forEach { number(it.key, 0L..1000L) }
        number("wait_ms", 0L..600_000L)
        number("duration_ms", when (action) {
            "virtual_screen_wait" -> 0L..600_000L
            "virtual_screen_long_press" -> 200L..5000L
            else -> 50L..5000L
        })
    }
}
