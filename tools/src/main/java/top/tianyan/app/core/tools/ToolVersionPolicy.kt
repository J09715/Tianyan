package top.tianyan.app.core.tools

internal fun isUpdateNewer(manifestLatest: String?, installedVersion: String?): Boolean {
    if (manifestLatest.isNullOrBlank() || installedVersion.isNullOrBlank()) return false
    val latestSemantic = top.tianyan.app.plugin.packages.SemanticVersion.parse(manifestLatest)
    val installedSemantic = top.tianyan.app.plugin.packages.SemanticVersion.parse(installedVersion)
    if (latestSemantic != null && installedSemantic != null) return latestSemantic > installedSemantic
    val latestNums = manifestLatest.versionNumbers() ?: return false
    val currentNums = installedVersion.versionNumbers() ?: return false
    for (i in 0 until maxOf(latestNums.size, currentNums.size)) {
        val latest = latestNums.getOrElse(i) { 0 }
        val current = currentNums.getOrElse(i) { 0 }
        if (latest != current) return latest > current
    }
    return false
}

private fun String.versionNumbers(): List<Int>? = Regex("\\d+(?:\\.\\d+){0,3}")
    .find(this)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
