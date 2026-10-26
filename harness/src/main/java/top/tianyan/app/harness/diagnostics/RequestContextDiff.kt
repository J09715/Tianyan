package top.tianyan.app.harness.diagnostics

data class RequestContextDiff(
    val added: List<String>,
    val removed: List<String>,
    val changed: List<String>,
    val unchangedCount: Int,
    val complete: Boolean,
) {
    companion object {
        /** Positional field comparison, not proof of identical wire requests or model context. */
        fun between(previous: RequestContextSnapshot, current: RequestContextSnapshot): RequestContextDiff {
            require(previous.sessionId == current.sessionId) { "Cannot compare different sessions" }
            val before = previous.sections.associateBy { it.label }
            val after = current.sections.associateBy { it.label }
            val shared = after.keys.filter { it in before }
            val changed = shared.filter { label ->
                val left = before.getValue(label)
                val right = after.getValue(label)
                if (left.fingerprint != null && right.fingerprint != null) left.fingerprint != right.fingerprint
                else left.preview != right.preview
            }
            return RequestContextDiff(
                added = after.keys.filter { it !in before },
                removed = before.keys.filter { it !in after },
                changed = changed,
                unchangedCount = shared.size - changed.size,
                complete = previous.omittedSectionCount == 0 && current.omittedSectionCount == 0 &&
                    (previous.sections + current.sections).all { it.fingerprint != null || !it.previewTruncated },
            )
        }
    }
}
