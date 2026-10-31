package top.tianyan.app.iteration.engine

import kotlinx.coroutines.delay
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * TianyanDevBuildCoordinator — 调度 GitHub Actions 云端构建与本地产物校验。
 *
 * 设计要点：
 * 1. 沙盒命令执行抽象为 [ShellRunner]：生产环境绑定 LinuxRuntime（遵循 runtime 模块
 *    ShellCommand/CommandResult 标准契约），纯 JVM 单元测试注入假执行器；
 * 2. 全流程 [runPipeline]：记录触发前的最新 run → 触发 workflow → 只接受更新的
 *    workflow_dispatch → 轮询状态 → 在沙盒下载目录创建目录并下载 artifact → SHA-256 校验；
 * 3. 每条 `gh` 命令都带 `-R` 仓库参数。分支名先做白名单校验，其余插值经 POSIX 单引号转义；
 * 4. UI 层只渲染状态，不做业务判断；失败细节（原始日志）只进 [BuildStatus.Failed.logs]，
 *    由调用方决定是否展示。
 */
class TianyanDevBuildCoordinator(
    private val shellRunner: ShellRunner,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 无法从工作区 `origin` 解析仓库时的兜底目标（默认官方仓库）。 */
    private val fallbackRepo: String = TianyanDevBuildCli.DEFAULT_REPO,
    /** 迭代工作区在沙盒内的路径，用于读取 `origin` remote。 */
    private val workspaceDir: String = TianyanDevBuildCli.DEFAULT_WORKSPACE_DIR,
) {

    /** 沙盒命令执行器抽象：生产绑定 LinuxRuntime，测试注入 Fake。 */
    fun interface ShellRunner {
        suspend fun execute(command: ShellCommand): CommandResult
    }

    sealed interface BuildStatus {
        data object Idle : BuildStatus
        data class Dispatching(val branch: String) : BuildStatus
        data class Running(val runId: Long, val statusText: String) : BuildStatus
        data class Downloading(val runId: Long, val progress: Int) : BuildStatus
        data class Success(val apkPath: String, val sha256: String) : BuildStatus
        data class Failed(val error: String, val logs: String? = null) : BuildStatus
    }

    /**
     * 端到端构建流水线：记录基线 → 触发 → 等待新 run → 轮询 → 下载 → 校验。
     * 全程通过 [onStatus] 上报状态，最终返回终态（[BuildStatus.Success] 或 [BuildStatus.Failed]）。
     *
     * @param branch 触发构建的特性分支
     * @param outputDir APK 下载目录（沙盒内可见路径，默认 `/sdcard/Download`）
     * @param pollIntervalMs 构建状态轮询间隔
     * @param maxPollAttempts 构建轮询上限（超过即超时失败）
     * @param maxRunWaitCycles 触发后等待新 run 注册的轮询次数
     * @param runRegisterDelayMs 新 run 注册轮询间隔
     * @param releaseTag 可选 Release 标签。空白表示不创建 Release；非法值在触发工作流之前失败关闭
     */
    suspend fun runPipeline(
        branch: String,
        outputDir: String = TianyanDevBuildCli.DEFAULT_OUTPUT_DIR,
        pollIntervalMs: Long = 10_000L,
        maxPollAttempts: Int = 120,
        maxRunWaitCycles: Int = 12,
        runRegisterDelayMs: Long = 5_000L,
        releaseTag: String? = null,
        onStatus: (BuildStatus) -> Unit,
    ): BuildStatus {
        TianyanDevBuildCli.branchRejection(branch)?.let { return BuildStatus.Failed(it) }
        TianyanDevBuildCli.outputDirRejection(outputDir)?.let { return BuildStatus.Failed(it) }
        TianyanDevBuildCli.repoRejection(fallbackRepo)?.let { return BuildStatus.Failed(it) }
        ReleaseTagValidator.validate(releaseTag)?.let { return BuildStatus.Failed(it) }
        val normalizedBranch = branch.trim()
        val normalizedDir = outputDir.trim()

        // 目标仓库来自工作区 origin：Fork 后 origin 指向主人的 Fork，
        // 写死官方仓库会让 workflow_dispatch 直接 403。解析不出就失败关闭。
        val repo = resolveRepo()
            ?: return BuildStatus.Failed(
                "无法确定构建目标仓库：工作区 $workspaceDir 的 origin 既不是可解析的 GitHub 仓库" +
                    "（需要 owner/name），也无法退回兜底仓库 $fallbackRepo。请先 Fork 并克隆到 $workspaceDir。",
            )
        TianyanDevBuildCli.repoRejection(repo)?.let { return BuildStatus.Failed(it) }

        onStatus(BuildStatus.Dispatching(normalizedBranch))

        val baselineList = execute(TianyanDevBuildCli.listRuns(branch = normalizedBranch, repo = repo))
        if (!baselineList.isSuccess) {
            return BuildStatus.Failed(
                error = "无法读取已有构建记录（exit=${baselineList.exitCode}）：${baselineList.stderr.trim().take(300)}",
                logs = baselineList.stderr.take(2_000),
            )
        }
        val knownRuns = TianyanDevBuildCli.parseRunList(baselineList.stdout)
            ?: return BuildStatus.Failed("无法解析已有构建记录，已中止以免把旧 run 当成新构建")
        val baselineId = knownRuns.maxOfOrNull { it.databaseId } ?: 0L
        val triggerAtMs = clock()

        val trigger = execute(
            commandLine = TianyanDevBuildCli.triggerWorkflow(
                branch = normalizedBranch,
                repo = repo,
                releaseTag = releaseTag,
            ),
            timeoutMs = 60_000L,
        )
        if (!trigger.isSuccess) {
            return BuildStatus.Failed(
                error = "触发工作流失败（exit=${trigger.exitCode}）：${trigger.stderr.trim().take(300)}",
                logs = trigger.stderr.take(2_000),
            )
        }

        val run = waitForNewRun(
            branch = normalizedBranch,
            repo = repo,
            baselineId = baselineId,
            triggerAtMs = triggerAtMs,
            maxAttempts = maxRunWaitCycles,
            delayMs = runRegisterDelayMs,
        ) ?: return BuildStatus.Failed(
            "未能获取构建 run：触发后未发现 id 大于 $baselineId 且事件为 workflow_dispatch 的新构建",
        )

        val outcome = pollUntilCompleted(run.databaseId, repo, pollIntervalMs, maxPollAttempts, onStatus)
        if (outcome.statusText != "completed") {
            return BuildStatus.Failed("构建超时未完成（最后状态：${outcome.statusText}，run #${run.databaseId}）")
        }
        if (outcome.conclusion != "success") {
            val logs = execute(TianyanDevBuildCli.checkFailedLog(run.databaseId, repo), timeoutMs = 60_000L)
            return BuildStatus.Failed(
                error = "构建失败（conclusion=${outcome.conclusion}，run #${run.databaseId}）",
                logs = logs.stdout.take(4_000).ifBlank { null },
            )
        }

        val runDir = TianyanDevBuildCli.runDownloadDir(normalizedDir, run.databaseId)
        TianyanDevBuildCli.outputDirRejection(runDir)?.let { return BuildStatus.Failed(it) }
        onStatus(BuildStatus.Downloading(run.databaseId, 0))
        val prepared = execute(TianyanDevBuildCli.prepareRunDownloadDir(runDir), timeoutMs = 30_000L)
        if (!prepared.isSuccess) {
            return BuildStatus.Failed(
                error = "创建下载目录失败（exit=${prepared.exitCode}）：${prepared.stderr.trim().take(300)}",
            )
        }
        val download = execute(
            commandLine = TianyanDevBuildCli.downloadArtifact(run.databaseId, runDir, repo),
            timeoutMs = 600_000L,
        )
        if (!download.isSuccess) {
            return BuildStatus.Failed(
                error = "下载 artifact 失败（exit=${download.exitCode}）：${download.stderr.trim().take(300)}",
            )
        }

        val expected = execute(
            commandLine = TianyanDevBuildCli.readChecksumFile(),
            workingDirectory = runDir,
            timeoutMs = 30_000L,
        ).stdout.trim().substringBefore(" ").take(64)
        val actual = execute(
            commandLine = TianyanDevBuildCli.computeChecksum(),
            workingDirectory = runDir,
            timeoutMs = 120_000L,
        ).stdout.trim().substringBefore(" ").take(64)
        if (expected.length != 64 || actual.length != 64 || !expected.equals(actual, ignoreCase = true)) {
            return BuildStatus.Failed(error = "APK SHA-256 校验失败：期望 $expected，实际 $actual")
        }

        val apkPath = "$runDir/${TianyanDevBuildCli.APK_NAME}"
        val success = BuildStatus.Success(apkPath = apkPath, sha256 = actual)
        onStatus(success)
        return success
    }

    /**
     * 解析云端构建的目标仓库。
     *
     * 优先级：工作区 `origin` 的 URL → [fallbackRepo]。
     * - `git remote get-url origin` 非零退出（尚未克隆）→ 使用 [fallbackRepo]；
     * - 读取成功但 URL 无法解析为 `owner/name` → 返回 null，调用方失败关闭，
     *   避免把不确定的仓库当成目标而触发无关仓库的 workflow。
     */
    private suspend fun resolveRepo(): String? {
        val origin = execute(
            commandLine = TianyanDevBuildCli.readOriginRemoteUrl(),
            workingDirectory = workspaceDir,
            timeoutMs = 30_000L,
        )
        if (!origin.isSuccess) {
            val candidate = TianyanDevBuildCli.effectiveRepo(null, fallbackRepo)
            return candidate.takeIf { TianyanDevBuildCli.repoRejection(it) == null }
        }
        // 读取到 URL 但无法解析成 owner/name 时失败关闭，不回退
        return TianyanDevBuildCli.parseGitHubSlug(origin.stdout)
    }

    /**
     * 在触发之后轮询 run list。两次查询之间休眠，发现合格的新 run 立即返回。
     * 次数与（间隔大于 0 时的）截止时间共同构成上界。
     */
    private suspend fun waitForNewRun(
        branch: String,
        repo: String,
        baselineId: Long,
        triggerAtMs: Long,
        maxAttempts: Int,
        delayMs: Long,
    ): GhWorkflowRun? {
        if (maxAttempts <= 0) return null
        val interval = delayMs.coerceAtLeast(0L)
        val budgetMs = maxAttempts.toLong() * interval
        val deadlineMs = if (budgetMs == 0L) Long.MAX_VALUE else clock() + budgetMs
        var attempt = 0
        while (attempt < maxAttempts && clock() <= deadlineMs) {
            val listed = execute(TianyanDevBuildCli.listRuns(branch = branch, repo = repo))
            if (listed.isSuccess) {
                val match = TianyanDevBuildCli.parseRunList(listed.stdout).orEmpty()
                    .filter { it.isNewRun(baselineId, triggerAtMs, branch) }
                    .maxByOrNull { it.databaseId }
                if (match != null) return match
            }
            attempt++
            if (attempt >= maxAttempts || clock() >= deadlineMs) break
            sleep(interval)
        }
        return null
    }

    private suspend fun pollUntilCompleted(
        runId: Long,
        repo: String,
        pollIntervalMs: Long,
        maxPollAttempts: Int,
        onStatus: (BuildStatus) -> Unit,
    ): RunOutcome {
        var statusText = "queued"
        var conclusion = "null"
        val attempts = maxPollAttempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            val status = execute(TianyanDevBuildCli.runStatus(runId, repo), timeoutMs = 60_000L)
            if (status.isSuccess) {
                val parsed = TianyanDevBuildCli.parseRunStatus(status.stdout)
                if (parsed != null) {
                    statusText = parsed.status.ifBlank { "unknown" }
                    conclusion = parsed.conclusion?.takeIf { it.isNotBlank() && it != "null" } ?: "null"
                    onStatus(BuildStatus.Running(runId, statusText))
                    if (statusText == "completed") break
                }
            }
            if (attempt < attempts) sleep(pollIntervalMs)
        }
        return RunOutcome(statusText, conclusion)
    }

    private suspend fun execute(
        commandLine: String,
        workingDirectory: String = "/root",
        timeoutMs: Long = ShellCommand.DEFAULT_TIMEOUT_MS,
    ): CommandResult = shellRunner.execute(
        ShellCommand(
            commandLine = commandLine,
            workingDirectory = workingDirectory,
            timeoutMs = timeoutMs,
        ),
    )

    private data class RunOutcome(val statusText: String, val conclusion: String)
}
