package top.tianyan.app.iteration.engine

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.common.shell.ShellQuote

/**
 * GitHub CLI 命令模板、分支校验与 run JSON 解析。
 *
 * 所有会进入 shell 的外部值都经 [ShellQuote] 做 POSIX 单引号转义；
 * 分支名另有白名单，拒绝在引号之前就无法构成合法 git ref 的输入。
 */
internal object TianyanDevBuildCli {
    const val WORKFLOW = "tianyandev-build.yml"
    const val ARTIFACT = "tianyandev-apk"
    const val APK_NAME = "TianyanDev-arm64-debug.apk"
    const val DEFAULT_REPO = "J09715/Tianyan"

    /** 唯一可信的代码托管主机；`origin` 指向其他主机时拒绝解析。 */
    const val GITHUB_HOST = "github.com"

    /**
     * PRoot 沙盒内可见的下载目录。
     *
     * [top.tianyan.app.runtime.LinuxRuntimeImpl] 在下载挂载开启时把宿主
     * `/storage/emulated/0/Download` 绑定到 guest `/sdcard/Download`；共享存储开启时则把
     * `/storage/emulated/0` 绑定到 `/sdcard`。宿主路径 `/storage/emulated/0/Download`
     * 在沙盒里没有对应目录。
     */
    const val DEFAULT_OUTPUT_DIR = "/sdcard/Download"
    /** 迭代工作区在沙盒内的绝对路径；`origin` remote 从这里读取（单一来源）。 */
    const val DEFAULT_WORKSPACE_DIR = CustomIterationBootstrap.GUEST_WORKSPACE
    const val RUN_LIST_LIMIT = 30

    /**
     * 手机时钟可能略快于 GitHub。时间戳比较放宽这一小段，避免刚触发的 run 被误判为过期；
     * 仍然要求 databaseId 大于触发前的基线，旧 run 不会因此被接受。
     */
    const val TRIGGER_CLOCK_SKEW_MS = 120_000L

    private val BRANCH_PATTERN = Regex("^[A-Za-z0-9._/-]+$")
    private val REPO_PATTERN = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    private val json = Json { ignoreUnknownKeys = true }

    /** 返回拒绝原因；空白、前导 `-`、`..` 以及白名单外字符都拒绝。合法时返回 null。 */
    fun branchRejection(raw: String): String? {
        val branch = raw.trim()
        if (branch.isEmpty()) return "分支名不能为空"
        if (branch.startsWith("-")) return "分支名不能以 - 开头"
        if (".." in branch) return "分支名不能包含 .."
        if (!BRANCH_PATTERN.matches(branch)) return "分支名含有不允许的字符"
        return null
    }

    /** 下载目录必须是沙盒内绝对路径，且不能用 `..` 跳出该目录。 */
    fun outputDirRejection(raw: String): String? {
        val dir = raw.trim()
        if (dir.isEmpty()) return "下载目录不能为空"
        if (!dir.startsWith("/")) return "下载目录必须是沙盒内的绝对路径"
        if (dir.any { it == '\n' || it == '\r' || it == '\u0000' }) return "下载目录含有非法字符"
        if (dir.split('/').any { it == ".." }) return "下载目录不能包含 .."
        return null
    }

    fun repoRejection(repo: String): String? =
        if (REPO_PATTERN.matches(repo)) null else "仓库标识必须是 owner/name"

    /**
     * 组装 `gh workflow run`。
     *
     * [releaseTag] 为空白时不传 `-f release_tag`（工作流视为不创建 Release）。
     * 非空时必须已通过 [ReleaseTagValidator]；非法值在这里再次失败关闭，避免把脏 tag 拼进命令。
     */
    fun triggerWorkflow(
        workflowName: String = WORKFLOW,
        branch: String = "main",
        repo: String = DEFAULT_REPO,
        releaseTag: String? = null,
    ): String {
        val tag = releaseTag?.trim().orEmpty()
        if (tag.isNotEmpty()) {
            ReleaseTagValidator.validate(tag)?.let { error(it) }
        }
        val releaseArg = if (tag.isEmpty()) "" else " -f release_tag=${q(tag)}"
        return "gh workflow run ${q(workflowName)} --ref ${q(branch)} -R ${q(repo)}$releaseArg"
    }

    fun watchWorkflow(runId: Long, repo: String = DEFAULT_REPO): String =
        "gh run watch $runId --exit-status -R ${q(repo)}"

    fun listRuns(
        workflowName: String = WORKFLOW,
        branch: String = "main",
        repo: String = DEFAULT_REPO,
    ): String = "gh run list --workflow ${q(workflowName)} --branch ${q(branch)} " +
        "--limit $RUN_LIST_LIMIT -R ${q(repo)} " +
        "--json databaseId,createdAt,status,conclusion,event,headBranch"

    fun runStatus(runId: Long, repo: String = DEFAULT_REPO): String =
        "gh run view $runId -R ${q(repo)} --json status,conclusion"

    fun downloadArtifact(
        runId: Long,
        outputDir: String = DEFAULT_OUTPUT_DIR,
        repo: String = DEFAULT_REPO,
    ): String = "gh run download $runId -n ${q(ARTIFACT)} -D ${q(outputDir)} -R ${q(repo)}"

    /**
     * 每次构建单独一个目录。`gh run download` 拒绝覆盖已有文件，
     * 旧 run 留在父目录里的 apk / .sha256 也不会被拿来和新产物比对。
     */
    fun runDownloadDir(outputDir: String, runId: Long): String =
        outputDir.trim().trimEnd('/') + "/tianyandev-" + runId

    /** 只删除这一次 run 的目录，再重建。父目录里其他文件不动。 */
    fun prepareRunDownloadDir(runDir: String): String =
        "rm -rf ${q(runDir)} && mkdir -p ${q(runDir)}"

    fun computeChecksum(apkName: String = APK_NAME): String =
        "sha256sum ${q(apkName)}"

    fun readChecksumFile(apkName: String = APK_NAME): String =
        "cat ${q("$apkName.sha256")}"

    fun checkFailedLog(runId: Long, repo: String = DEFAULT_REPO): String =
        "gh run view $runId --log-failed -R ${q(repo)}"

    /**
     * 解析 `gh run list --json` 的 stdout。
     * 空输出视为没有 run；非法 JSON 返回 null，调用方必须失败关闭，不能把解析失败当成「没有旧 run」。
     */
    fun parseRunList(stdout: String): List<GhWorkflowRun>? {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<GhWorkflowRun>>(trimmed) }.getOrNull()
    }

    fun parseRunStatus(stdout: String): GhRunStatus? {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.decodeFromString<GhRunStatus>(trimmed) }.getOrNull()
    }

    /** 读取工作区 `origin` 的远程 URL；工作区尚未克隆时应失败关闭。 */
    fun readOriginRemoteUrl(): String = "git remote get-url origin"

    /**
     * 从 GitHub 远程 URL 解析 `owner/name`。
     *
     * 覆盖 HTTPS / `git@host:owner/name`（scp-like）/ `ssh://[user@]host[:port]/owner/name` 三类形态，
     * 剥离 `.git` 后缀与尾斜杠。主机必须是 `github.com`，结果必须满足 [REPO_PATTERN]，
     * 否则返回 null —— 调用方据此失败关闭，绝不把不确定的仓库当成目标。
     */
    fun parseGitHubSlug(remoteUrl: String): String? {
        val url = remoteUrl.trim().removeSuffix("/")
        if (url.isEmpty()) return null

        val isScpLike = url.startsWith("git@")
        val afterScheme = when {
            url.startsWith("https://", true) -> url.substringAfter("//")
            url.startsWith("http://", true) -> url.substringAfter("//")
            url.startsWith("ssh://", true) -> url.substringAfter("//")
            isScpLike -> url.substringAfter('@')
            else -> return null
        }
        // 去掉可能存在的 userinfo（ssh://git@github.com/...）
        val hostAndPath = afterScheme.substringAfterLast('@')
        if (hostAndPath.isEmpty()) return null

        val path: String
        val hostPart: String
        if (isScpLike && ':' in hostAndPath) {
            // scp-like：git@host:owner/name（无端口概念）
            hostPart = hostAndPath.substringBefore(':')
            path = hostAndPath.substringAfter(':')
        } else {
            // host[:port]/owner/name —— 端口只出现在第一个 '/' 之前，取 '/' 之后即得路径
            hostPart = hostAndPath.substringBefore('/').substringBefore(':')
            path = hostAndPath.substringAfter('/', "")
        }

        // 只接受 github.com：其他主机的 owner/name 不能用于本应用的 GitHub Actions 构建
        if (!hostPart.equals(GITHUB_HOST, ignoreCase = true)) return null

        val slug = path.removeSuffix(".git").trim().trim('/')
        if (slug.isEmpty()) return null
        if (slug.split('/').size != 2) return null
        return slug.takeIf { REPO_PATTERN.matches(it) }
    }

    /** 触发/轮询/下载统一使用的仓库解析优先级：工作区 remote 优先，缺失时退回官方仓库。 */
    fun effectiveRepo(resolved: String?, fallback: String): String {
        val candidate = resolved?.trim().orEmpty()
        return if (candidate.isNotEmpty() && REPO_PATTERN.matches(candidate)) candidate else fallback
    }

    private fun q(value: String): String = ShellQuote.of(value)
}

@Serializable
internal data class GhWorkflowRun(
    val databaseId: Long = 0L,
    val createdAt: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val event: String = "",
    val headBranch: String = "",
) {
    fun createdAtEpochMs(): Long? = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrNull()

    /** 新构建必须同时满足：id 大于基线、创建时间不早于触发时刻、事件为 workflow_dispatch、分支一致。 */
    fun isNewRun(baselineId: Long, triggerAtMs: Long, branch: String): Boolean {
        if (databaseId <= baselineId) return false
        if (event != "workflow_dispatch") return false
        if (headBranch != branch) return false
        val created = createdAtEpochMs() ?: return false
        return created >= triggerAtMs - TianyanDevBuildCli.TRIGGER_CLOCK_SKEW_MS
    }
}

@Serializable
internal data class GhRunStatus(
    val status: String = "",
    val conclusion: String? = null,
)
