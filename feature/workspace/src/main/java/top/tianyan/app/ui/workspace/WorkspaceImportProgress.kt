package top.tianyan.app.ui.workspace

private val gitPercentRegex = Regex("""(\d{1,3})%""")

/** Git refreshes progress with carriage returns; the last nonblank segment is current. */
internal fun workspaceImportProgress(chunk: String): WorkspaceViewModel.GithubImportProgress? {
    val latest = chunk.split('\r', '\n').lastOrNull { it.isNotBlank() }?.trim() ?: return null
    return WorkspaceViewModel.GithubImportProgress(
        text = latest,
        percent = gitPercentRegex.findAll(latest).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100),
    )
}
