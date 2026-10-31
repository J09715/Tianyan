package top.tianyan.app.core.tools

/** Local package releases have their own version, independent from bundled command output. */
internal fun installedToolVersion(manifest: top.tianyan.app.core.model.ToolManifest, reported: String?): String =
    if (manifest.source == "LOCAL" || manifest.schemaVersion == 2) manifest.version
    else reported?.trim()?.takeIf { it.isNotBlank() } ?: manifest.version

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
