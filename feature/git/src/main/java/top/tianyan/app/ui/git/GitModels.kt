package top.tianyan.app.ui.git

/** 分支信息：本地 or 远程跟踪 */
data class GitBranchInfo(
    val fullName: String,
    val shortName: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val commitId: String,
)

enum class GitRefType { HEAD, BRANCH, REMOTE, TAG }

data class GitRefLabel(val name: String, val type: GitRefType)

/** 仓库概览：当前分支 / 分支列表 / 远程 / 工作区状态 */
data class GitOverview(
    val currentBranch: String,
    val isDetached: Boolean,
    val localBranches: List<GitBranchInfo>,
    val remoteBranches: List<GitBranchInfo>,
    val remoteUrl: String,
    val aheadCount: Int,
    val behindCount: Int,
    val uncommittedChanges: Int,
    val untrackedFiles: Int,
)

/**
 * 提交记录树的绘制几何信息：
 * - [lane] 本提交所在的泳道
 * - [preStraight] 行上半段竖直穿过的泳道（与本提交无关的分支）
 * - [mergeInPositions] 从其他泳道汇入本节点的泳道（合并点上半段斜线）
 * - [parentLanes] 本节点向下连出的父提交所在泳道（下半段斜线/竖线）
 * - [postStraight] 行下半段竖直穿过的泳道
 * - [laneCount] 本行最大泳道数（决定画布宽度）
 */
data class GitCommitRow(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val commitTime: Long,
    val refs: List<GitRefLabel>,
    val lane: Int,
    val preStraight: List<Int>,
    val mergeInPositions: List<Int>,
    val parentLanes: List<Int>,
    val postStraight: List<Int>,
    val laneCount: Int,
)

/** push / pull 进度 */
data class GitProgress(val title: String, val completed: Int, val total: Int)
