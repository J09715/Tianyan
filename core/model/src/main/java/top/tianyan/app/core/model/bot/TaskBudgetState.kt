package top.tianyan.app.core.model.bot

data class TaskBudgetState(
    val id: String,
    val groupId: String,
    val title: String,
    val limitTokens: Long,
    val usedTokens: Long,
    val heldTokens: Long,
    val unknownRequests: Int,
    val createdAt: Long,
) {
    val availableTokens: Long get() = (limitTokens - usedTokens - heldTokens).coerceAtLeast(0)
    companion object { const val MAX_LIMIT = 1_000_000_000_000L }
}

class TaskBudgetExceededException(message: String) : IllegalStateException(message)
