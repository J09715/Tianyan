package top.tianyan.app.ui.chat

import java.io.File

/**
 * 交付产物路径解析（纯函数，便于单测）。
 *
 * harness 侧的 `ToolFileArtifacts.relativePath` 已经把会话工作区根剥掉，
 * 卡片这边只负责把「会话 workspace + 相对路径」还原成宿主文件。
 */
internal object DeliveryArtifactPaths {

    /** 会话 `workspace`（形如 `/workspace/<project>`）→ 项目名；根目录与越界段一律返回 null。 */
    fun projectName(workspace: String): String? {
        val normalized = workspace.trim().replace('\\', '/').trimEnd('/').trimStart('/')
        if (normalized.isEmpty() || normalized == "workspace") return null
        val tail = normalized.removePrefix("workspace/")
        val project = tail.substringBefore('/')
        if (project.isBlank() || project == "." || project == "..") return null
        return project
    }

    fun hostFile(workspace: String, relativePath: String, workspaceRoot: File?): File? {
        if (workspaceRoot == null) return null
        val project = projectName(workspace) ?: return null
        val normalized = relativePath.trim().replace('\\', '/').removePrefix("/")
        if (normalized.isBlank()) return null
        if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(File(workspaceRoot, project), normalized)
    }
}