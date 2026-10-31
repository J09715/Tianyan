package top.tianyan.app.core.model.bot

/** Full editable contents; revision distinguishes a missing file from an empty file. */
data class BotMemoryDocument(val path: String, val content: String, val revision: String)
data class BotMemoryDiff(val hash: String, val text: String, val truncated: Boolean)
data class BotMemoryRestore(val backupHash: String, val restoredHash: String)
