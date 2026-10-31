package top.tianyan.app.ui.chat

import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.WorkspaceProject

/** 按项目划分的会话组数据结构。 */
data class ProjectSessionGroup(
    val projectName: String,
    val workspacePath: String,
    val projectType: ProjectType,
    val sessions: List<HarnessSessionEntity>,
)

/** 会话分区结果：项目分组与未关联的最近会话。 */
data class SessionPartitionResult(
    val projectGroups: List<ProjectSessionGroup>,
    val recentSessions: List<HarnessSessionEntity>,
)

/**
 * 将全部会话与工作区进行归集与分区：
 * 1. 关联了工作区/项目的会话归类入对应项目分组（按 updatedAt 倒序）。
 * 2. 未关联工作区的纯沙箱会话归入「最近」（按 updatedAt 倒序）。
 */
fun partitionSessions(
    sessions: List<HarnessSessionEntity>,
    workspaces: List<WorkspaceProject>,
): SessionPartitionResult {
    val (withWs, withoutWs) = sessions.partition { it.workspace.isNotBlank() }
    val recentSessions = withoutWs.sortedByDescending { it.updatedAt }
    val sessionsByWs = withWs.groupBy { it.workspace.trimEnd('/') }
    val handledWsPaths = mutableSetOf<String>()
    val projectGroups = mutableListOf<ProjectSessionGroup>()

    for (ws in workspaces) {
        val normPath = ws.linuxPath.trimEnd('/')
        handledWsPaths.add(normPath)
        val projSessions = sessionsByWs[normPath].orEmpty().sortedByDescending { it.updatedAt }
        projectGroups.add(ProjectSessionGroup(ws.name, ws.linuxPath, ws.projectType, projSessions))
    }

    for ((wsPath, orphanSessions) in sessionsByWs) {
        if (!handledWsPaths.contains(wsPath)) {
            val extractedName = wsPath.substringAfterLast('/').ifBlank { wsPath }
            projectGroups.add(ProjectSessionGroup(extractedName, wsPath, ProjectType.GENERAL, orphanSessions.sortedByDescending { it.updatedAt }))
        }
    }

    val sortedProjectGroups = projectGroups.sortedWith(
        compareByDescending<ProjectSessionGroup> { it.sessions.firstOrNull()?.updatedAt ?: 0L }
            .thenBy { it.projectName.lowercase() },
    )
    return SessionPartitionResult(sortedProjectGroups, recentSessions)
}
