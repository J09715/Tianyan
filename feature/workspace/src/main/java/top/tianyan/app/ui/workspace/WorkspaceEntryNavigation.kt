package top.tianyan.app.ui.workspace

/**
 * 文件/文件夹名称合法性校验（UI 与 VM 共用同一规则）：
 * 不允许路径分隔符（/ 与 \\）、首尾空格、以 `.` 开头（隐藏文件）以及 `.` / `..` 等特殊名称。
 */
internal fun isValidWorkspaceEntryName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (name.trim() != name) return false
    if (name.startsWith(".")) return false
    if (name == "." || name == "..") return false
    if (name.contains('/') || name.contains('\\')) return false
    return true
}

/**
 * 计算文件浏览器跳转或重入时的目标路径：
 * - 切换到不同项目：使用目标路径（未指定时为根目录 ""）；
 * - 同一项目重入且未显式指定子目录（targetPath 为空）：保留已有子目录，避免打开代码编辑等页面返回后被重置到根目录；
 * - 同一项目显式传入非空子目录：跳转到该目标路径；
 * - 同一项目当前处于根目录且 targetPath 为空：保持根目录。
 */
internal fun computeExplorerPath(
    currentProject: String?,
    currentPath: String,
    targetProject: String,
    targetPath: String,
): String {
    val cleanTarget = targetPath.trim().removePrefix("/")
    val isSameProject = currentProject == targetProject
    return if (!isSameProject) {
        cleanTarget
    } else if (cleanTarget.isNotBlank() || currentPath.isBlank()) {
        cleanTarget
    } else {
        currentPath
    }
}
