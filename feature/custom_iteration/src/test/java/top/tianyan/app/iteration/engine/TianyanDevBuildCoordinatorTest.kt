package top.tianyan.app.iteration.engine

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator.BuildStatus
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * TianyanDevBuildCoordinator 纯 JVM 单元测试：
 * 命令模板、基线 run 过滤、轮询退出、shell 注入与仓库参数。
 */
class TianyanDevBuildCoordinatorTest {

    private companion object {
        const val SHA_OK = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        const val SHA_BAD = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
        const val TRIGGER_MS = 1_760_000_000_000L
        const val BASELINE_ID = 7L
        const val NEW_RUN_ID = 42L
    }

    private class FakeShellRunner : TianyanDevBuildCoordinator.ShellRunner {
        val commands = mutableListOf<ShellCommand>()

        var triggerResult = CommandResult(0, "", "", 0)
        val runListResults = ArrayDeque<CommandResult>()
        val runStatusResults = ArrayDeque<CommandResult>()
        var failedLogResult = CommandResult(0, "# Error log", "", 0)
        var downloadResult = CommandResult(0, "", "", 0)
        var mkdirResult = CommandResult(0, "", "", 0)
        var checksumResult = CommandResult(0, "$SHA_OK  TianyanDev-arm64-debug.apk", "", 0)
        var checksumFileResult = CommandResult(0, "$SHA_OK  output/TianyanDev-arm64-debug.apk", "", 0)
        /** `git remote get-url origin` 的结果；默认失败 → 走 fallbackRepo。 */
        var originResult: CommandResult = CommandResult(1, "", "error: No such remote 'origin'", 0)

        override suspend fun execute(command: ShellCommand): CommandResult {
            commands += command
            val line = command.commandLine
            return when {
                line.startsWith("gh workflow run") -> triggerResult
                line.startsWith("gh run list") -> runListResults.removeFirstOrNull()
                    ?: CommandResult(0, "[]", "", 0)
                line.startsWith("gh run view") && "--log-failed" in line -> failedLogResult
                line.startsWith("gh run view") -> runStatusResults.removeFirstOrNull()
                    ?: CommandResult(0, """{"status":"completed","conclusion":"success"}""", "", 0)
                line.startsWith("gh run download") -> downloadResult
                line.startsWith("rm ") || line.startsWith("mkdir ") -> mkdirResult
                line.startsWith("sha256sum") -> checksumResult
                line.startsWith("cat ") -> checksumFileResult
                line.startsWith("git remote get-url") -> originResult
                else -> CommandResult(1, "", "unexpected: $line", 0)
            }
        }
    }

    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun runJson(
        id: Long,
        createdAtMs: Long,
        status: String,
        conclusion: String?,
        event: String = "workflow_dispatch",
        branch: String,
    ): String {
        val conclusionJson = conclusion?.let { "\"$it\"" } ?: "null"
        return """{"databaseId":$id,"createdAt":"${iso(createdAtMs)}","status":"$status",""" +
            """"conclusion":$conclusionJson,"event":"$event","headBranch":"$branch"}"""
    }

    private fun okJson(body: String) = CommandResult(0, body, "", 0)

    private fun oldRun(branch: String, id: Long = BASELINE_ID): String = runJson(
        id = id,
        createdAtMs = TRIGGER_MS - 3_600_000,
        status = "completed",
        conclusion = "success",
        branch = branch,
    )

    private fun newRun(
        branch: String,
        id: Long = NEW_RUN_ID,
        status: String = "queued",
        createdAtMs: Long = TRIGGER_MS,
        event: String = "workflow_dispatch",
    ): String = runJson(
        id = id,
        createdAtMs = createdAtMs,
        status = status,
        conclusion = if (status == "completed") "success" else null,
        event = event,
        branch = branch,
    )

    /** 第一次 list 只有基线 run，之后的 list 带上新 run。 */
    private fun FakeShellRunner.seedNewRun(branch: String, fresh: String = newRun(branch)) {
        val baseline = oldRun(branch)
        runListResults += okJson("[$baseline]")
        runListResults += okJson("[$baseline,$fresh]")
    }

    private fun coordinator(
        fake: FakeShellRunner,
        sleeps: MutableList<Long>? = null,
        repo: String = TianyanDevBuildCli.DEFAULT_REPO,
    ) = TianyanDevBuildCoordinator(
        shellRunner = fake,
        sleep = { delayMs -> sleeps?.add(delayMs) },
        clock = { TRIGGER_MS },
        fallbackRepo = repo,
    )

    private suspend fun TianyanDevBuildCoordinator.runFast(
        branch: String = "feature/x",
        outputDir: String = TianyanDevBuildCli.DEFAULT_OUTPUT_DIR,
        maxRunWaitCycles: Int = 3,
        runRegisterDelayMs: Long = 0L,
        releaseTag: String? = null,
        onStatus: (BuildStatus) -> Unit = {},
    ): BuildStatus = runPipeline(
        branch = branch,
        outputDir = outputDir,
        pollIntervalMs = 0L,
        maxPollAttempts = 5,
        maxRunWaitCycles = maxRunWaitCycles,
        runRegisterDelayMs = runRegisterDelayMs,
        releaseTag = releaseTag,
        onStatus = onStatus,
    )

    @Test
    fun `cli templates quote arguments and pin the repository`() {
        val trigger = TianyanDevBuildCli.triggerWorkflow(branch = "feature/ci-build-coordinator")
        assertTrue(trigger.contains("gh workflow run 'tianyandev-build.yml'"))
        assertTrue(trigger.contains("--ref 'feature/ci-build-coordinator'"))
        assertTrue(trigger.contains("-R 'J09715/Tianyan'"))
        assertFalse(trigger.contains("release_tag"))
        assertFalse(TianyanDevBuildCli.triggerWorkflow(releaseTag = "   ").contains("release_tag"))
        assertTrue(
            TianyanDevBuildCli.triggerWorkflow(releaseTag = " v0.2.1-dev ")
                .contains("-f release_tag='v0.2.1-dev'"),
        )
        val rejected = runCatching { TianyanDevBuildCli.triggerWorkflow(releaseTag = "v0.2.1-dev; rm -rf /") }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertEquals(
            ReleaseTagValidator.validate("v0.2.1-dev; rm -rf /"),
            rejected.exceptionOrNull()?.message,
        )

        val latest = TianyanDevBuildCli.listRuns(branch = "feature/x")
        assertTrue(latest.contains("--workflow 'tianyandev-build.yml'"))
        assertTrue(latest.contains("--branch 'feature/x'"))
        assertTrue(latest.contains("-R 'J09715/Tianyan'"))
        assertTrue(latest.contains("databaseId,createdAt,status,conclusion,event,headBranch"))
        assertFalse(latest.contains("--jq"))

        assertTrue(TianyanDevBuildCli.runStatus(42L).contains("gh run view 42 -R 'J09715/Tianyan'"))
        assertTrue(
            TianyanDevBuildCli.downloadArtifact(42L, "/sdcard/Download")
                .contains("gh run download 42 -n 'tianyandev-apk' -D '/sdcard/Download' -R 'J09715/Tianyan'"),
        )
        assertTrue(TianyanDevBuildCli.watchWorkflow(42L).contains("-R 'J09715/Tianyan'"))
        assertTrue(TianyanDevBuildCli.checkFailedLog(42L).contains("--log-failed -R 'J09715/Tianyan'"))
        assertEquals("sha256sum 'TianyanDev-arm64-debug.apk'", TianyanDevBuildCli.computeChecksum())
        assertEquals("cat 'TianyanDev-arm64-debug.apk.sha256'", TianyanDevBuildCli.readChecksumFile())
        assertEquals(
            "/sdcard/Download/tianyandev-42",
            TianyanDevBuildCli.runDownloadDir("/sdcard/Download", 42L),
        )
        assertEquals(
            "rm -rf '/sdcard/Download/tianyandev-42' && mkdir -p '/sdcard/Download/tianyandev-42'",
            TianyanDevBuildCli.prepareRunDownloadDir("/sdcard/Download/tianyandev-42"),
        )
        assertEquals("/sdcard/Download", TianyanDevBuildCli.DEFAULT_OUTPUT_DIR)
    }

    @Test
    fun `pipeline happy path emits states and returns success with apk path and sha`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            runStatusResults += okJson("""{"status":"in_progress","conclusion":null}""")
            runStatusResults += okJson("""{"status":"completed","conclusion":"success"}""")
        }
        val states = mutableListOf<BuildStatus>()
        val final = coordinator(fake).runFast { states += it }

        assertTrue(final is BuildStatus.Success)
        final as BuildStatus.Success
        assertEquals("/sdcard/Download/tianyandev-42/TianyanDev-arm64-debug.apk", final.apkPath)
        assertEquals(SHA_OK, final.sha256)
        assertTrue(states.first() is BuildStatus.Dispatching)
        assertTrue(states.any { it is BuildStatus.Running && it.runId == NEW_RUN_ID })
        assertTrue(states.any { it is BuildStatus.Downloading })
        assertTrue(states.last() is BuildStatus.Success)
        val triggerLine = fake.commands.first { it.commandLine.startsWith("gh workflow run") }.commandLine
        assertTrue(triggerLine.contains("--ref 'feature/x'"))
        assertFalse(triggerLine.contains("release_tag"))
    }

    @Test
    fun `invalid release tag is rejected before gh workflow run`() = runTest {
        listOf(
            "v0.2.1-dev; rm -rf /",
            "v0.2.1-dev\$(whoami)",
            "v1.0.0_rc1",
            "v0.2.1-",
            "0.2.1-dev",
        ).forEach { tag ->
            val fake = FakeShellRunner()
            val result = coordinator(fake).runFast(releaseTag = tag)
            assertTrue("expected rejection for [$tag]", result is BuildStatus.Failed)
            assertEquals(ReleaseTagValidator.validate(tag), (result as BuildStatus.Failed).error)
            assertTrue("commands ran for [$tag]", fake.commands.isEmpty())
        }
    }

    @Test
    fun `valid release tag is forwarded only after validation`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            runStatusResults += okJson("""{"status":"completed","conclusion":"success"}""")
        }
        val result = coordinator(fake).runFast(releaseTag = " v0.2.1-dev ")
        assertTrue(result is BuildStatus.Success)
        val trigger = fake.commands.first { it.commandLine.startsWith("gh workflow run") }.commandLine
        assertTrue(trigger.contains("-f release_tag='v0.2.1-dev'"))
        assertFalse(trigger.contains("-f release_tag=' v0.2.1-dev '"))
    }

    @Test
    fun `baseline run is ignored and the newer workflow_dispatch is used`() = runTest {
        val branch = "feature/x"
        val fake = FakeShellRunner().apply {
            val stale = oldRun(branch, id = 42L)
            val fresh = newRun(branch, id = 99L, status = "queued")
            runListResults += okJson("[$stale]")
            runListResults += okJson("[$stale,$fresh]")
            runStatusResults += okJson("""{"status":"completed","conclusion":"success"}""")
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Success)
        val lines = fake.commands.map { it.commandLine }
        assertTrue(lines.any { it.startsWith("gh run view 99 ") })
        assertTrue(lines.any { it.startsWith("gh run download 99 ") })
        assertFalse(lines.any { it.startsWith("gh run view 42 ") || it.startsWith("gh run download 42 ") })
    }

    @Test
    fun `already completed baseline run is not treated as the new build`() = runTest {
        val branch = "feature/x"
        val fake = FakeShellRunner().apply {
            val stale = oldRun(branch, id = 42L)
            repeat(4) { runListResults += okJson("[$stale]") }
        }
        val final = coordinator(fake).runFast(maxRunWaitCycles = 3, runRegisterDelayMs = 0L)

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("未能获取构建 run"))
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh run download") })
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh run view") })
    }

    @Test
    fun `higher id created before the trigger is ignored`() = runTest {
        val branch = "feature/x"
        val fake = FakeShellRunner().apply {
            val baseline = oldRun(branch, id = 7L)
            val tooEarly = newRun(branch, id = 42L, createdAtMs = TRIGGER_MS - 3_600_000)
            runListResults += okJson("[$baseline]")
            repeat(3) { runListResults += okJson("[$baseline,$tooEarly]") }
        }
        val final = coordinator(fake).runFast(maxRunWaitCycles = 3)

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("未能获取构建 run"))
    }

    @Test
    fun `push event is skipped until a workflow_dispatch run appears`() = runTest {
        val branch = "feature/x"
        val sleeps = mutableListOf<Long>()
        val fake = FakeShellRunner().apply {
            val baseline = oldRun(branch, id = 10L)
            val pushed = newRun(branch, id = 11L, event = "push")
            val dispatched = newRun(branch, id = 12L)
            runListResults += okJson("[$baseline]")
            runListResults += okJson("[$baseline,$pushed]")
            runListResults += okJson("[$baseline,$pushed,$dispatched]")
        }
        val final = coordinator(fake, sleeps).runFast(
            maxRunWaitCycles = 4,
            runRegisterDelayMs = 5_000L,
        )

        assertTrue(final is BuildStatus.Success)
        assertTrue(fake.commands.any { it.commandLine.startsWith("gh run view 12 ") })
        assertFalse(fake.commands.any { it.commandLine.startsWith("gh run view 11 ") })
        assertEquals(listOf(5_000L), sleeps.filter { it == 5_000L })
    }

    @Test
    fun `registration loop stops when the new run is found and times out otherwise`() = runTest {
        val branch = "feature/x"
        val foundSleeps = mutableListOf<Long>()
        val found = FakeShellRunner().apply { seedNewRun(branch) }
        val foundResult = coordinator(found, foundSleeps).runFast(
            maxRunWaitCycles = 5,
            runRegisterDelayMs = 5_000L,
        )
        assertTrue(foundResult is BuildStatus.Success)
        assertEquals(2, found.commands.count { it.commandLine.startsWith("gh run list") })
        assertTrue(foundSleeps.none { it == 5_000L })

        val timeoutSleeps = mutableListOf<Long>()
        val timeout = FakeShellRunner().apply {
            val stale = oldRun(branch)
            repeat(4) { runListResults += okJson("[$stale]") }
        }
        val timeoutResult = coordinator(timeout, timeoutSleeps).runFast(
            maxRunWaitCycles = 3,
            runRegisterDelayMs = 5_000L,
        )
        assertTrue(timeoutResult is BuildStatus.Failed)
        assertEquals(4, timeout.commands.count { it.commandLine.startsWith("gh run list") })
        assertEquals(listOf(5_000L, 5_000L), timeoutSleeps)
        assertTrue(timeout.commands.any { it.commandLine.startsWith("gh workflow run") })
        assertTrue(timeout.commands.none { it.commandLine.startsWith("gh run download") })
    }

    @Test
    fun `shell metacharacters in the branch are rejected before any command`() = runTest {
        val attacks = listOf(
            "main; rm -rf /",
            "feature/../../etc",
            "feature/../main",
            "-rf",
            "--help",
            "main && true",
            "main\nid",
            "feat`id`",
            "feat$(id)",
            "feat'x",
            "a b",
            "",
            " ",
            "..",
        )
        attacks.forEach { branch ->
            val fake = FakeShellRunner()
            val result = coordinator(fake).runFast(branch = branch)
            assertTrue("expected rejection for [$branch]", result is BuildStatus.Failed)
            assertTrue((result as BuildStatus.Failed).error.isNotBlank())
            assertTrue("commands ran for [$branch]", fake.commands.isEmpty())
        }
    }

    @Test
    fun `interpolated paths are posix single quoted`() = runTest {
        val outputDir = "/sdcard/Download/o'brien"
        val fake = FakeShellRunner().apply { seedNewRun("feature/x") }
        val final = coordinator(fake).runFast(outputDir = outputDir)

        assertTrue(final is BuildStatus.Success)
        val quoted = "'/sdcard/Download/o'\\''brien/tianyandev-42'"
        val lines = fake.commands.map { it.commandLine }
        assertTrue(lines.any { it == "rm -rf $quoted && mkdir -p $quoted" })
        assertTrue(lines.any { it.contains("-D $quoted") })
        assertTrue(lines.any { it == "sha256sum 'TianyanDev-arm64-debug.apk'" })
        assertTrue(lines.any { it == "cat 'TianyanDev-arm64-debug.apk.sha256'" })
        assertTrue(lines.none { it.contains("o'brien") && !it.contains("'\\''") })
    }

    @Test
    fun `every gh command targets tianyan tianyan`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            runStatusResults += okJson("""{"status":"completed","conclusion":"failure"}""")
        }
        coordinator(fake).runFast()

        val ghCommands = fake.commands.map { it.commandLine }.filter { it.startsWith("gh ") }
        assertTrue(ghCommands.isNotEmpty())
        ghCommands.forEach { line ->
            assertTrue(line, line.contains("-R 'J09715/Tianyan'"))
        }
    }

    @Test
    fun `origin remote overrides the fallback repository`() = runTest {
        val fake = FakeShellRunner().apply {
            originResult = CommandResult(0, "https://github.com/haotaoran520/tianyan.git\n", "", 0)
            seedNewRun("feature/x")
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Success)
        val lines = fake.commands.map { it.commandLine }
        assertTrue(
            "every gh command must target the fork: $lines",
            lines.filter { it.startsWith("gh ") }.all { it.contains("-R 'haotaoran520/tianyan'") },
        )
        assertTrue(lines.none { it.contains("-R 'J09715/Tianyan'") })
        assertTrue(lines.any { it == "git remote get-url origin" })
    }

    @Test
    fun `unparseable origin fails closed instead of hitting the official repo`() = runTest {
        val fake = FakeShellRunner().apply {
            originResult = CommandResult(0, "https://gitlab.com/someone/tianyan.git\n", "", 0)
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue(
            "must explain the repo could not be resolved: ${(final as BuildStatus.Failed).error}",
            final.error.contains("无法确定构建目标仓库"),
        )
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh ") })
    }

    @Test
    fun `missing origin falls back to the official repository`() = runTest {
        val fake = FakeShellRunner().apply { seedNewRun("feature/x") }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Success)
        assertTrue(fake.commands.any { it.commandLine == "git remote get-url origin" })
        assertTrue(
            fake.commands.filter { it.commandLine.startsWith("gh ") }
                .all { it.commandLine.contains("-R 'J09715/Tianyan'") },
        )
    }

    @Test
    fun `remote url parsing covers https ssh and scp forms`() {
        val cases = mapOf(
            "https://github.com/owner/name.git" to "owner/name",
            "https://github.com/owner/name" to "owner/name",
            "https://github.com/owner/name/" to "owner/name",
            "git@github.com:owner/name.git" to "owner/name",
            "ssh://git@github.com/owner/name.git" to "owner/name",
            "ssh://git@github.com:22/owner/name.git" to "owner/name",
        )
        cases.forEach { (url, expected) ->
            assertEquals("url=$url", expected, TianyanDevBuildCli.parseGitHubSlug(url))
        }
        listOf(
            "",
            "https://gitlab.com/owner/name.git",
            "https://github.com/owner",
            "https://github.com/a/b/c.git",
            "not-a-url",
        ).forEach { assertNull("url=$it", TianyanDevBuildCli.parseGitHubSlug(it)) }
    }

    @Test
    fun `custom repo is quoted on every gh command`() = runTest {
        val fake = FakeShellRunner().apply { seedNewRun("feature/x") }
        coordinator(fake, repo = "octo/demo").runFast()

        fake.commands.map { it.commandLine }.filter { it.startsWith("gh ") }.forEach { line ->
            assertTrue(line, line.contains("-R 'octo/demo'"))
        }
    }

    @Test
    fun `pipeline fails when workflow dispatch fails`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            triggerResult = CommandResult(1, "", "gh: auth error", 0)
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("触发工作流失败"))
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh run download") })
    }

    @Test
    fun `pipeline fails when run never registers`() = runTest {
        val fake = FakeShellRunner().apply {
            repeat(4) { runListResults += okJson("[]") }
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("未能获取构建 run"))
    }

    @Test
    fun `unparseable baseline list does not dispatch`() = runTest {
        val fake = FakeShellRunner().apply {
            runListResults += okJson("not-json")
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("无法解析已有构建记录"))
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh workflow run") })
    }

    @Test
    fun `pipeline fails with logs when build conclusion is failure`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            runStatusResults += okJson("""{"status":"completed","conclusion":"failure"}""")
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertEquals("构建失败（conclusion=failure，run #42）", (final as BuildStatus.Failed).error)
        assertTrue((final as BuildStatus.Failed).logs.orEmpty().contains("Error log"))
    }

    @Test
    fun `pipeline fails when artifact download fails`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            downloadResult = CommandResult(1, "", "not found", 0)
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("下载 artifact 失败"))
    }

    @Test
    fun `mkdir failure stops before download`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            mkdirResult = CommandResult(1, "", "read-only", 0)
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("创建下载目录失败"))
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh run download") })
    }

    @Test
    fun `pipeline fails on checksum mismatch`() = runTest {
        val fake = FakeShellRunner().apply {
            seedNewRun("feature/x")
            checksumResult = CommandResult(0, "$SHA_BAD  TianyanDev-arm64-debug.apk", "", 0)
        }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Failed)
        val failed = final as BuildStatus.Failed
        assertTrue(failed.error.contains("SHA-256 校验失败"))
        assertTrue(failed.error.contains(SHA_OK))
        assertTrue(failed.error.contains(SHA_BAD))
    }

    @Test
    fun `checksum commands run inside output dir after mkdir`() = runTest {
        val fake = FakeShellRunner().apply { seedNewRun("feature/x") }
        coordinator(fake).runFast()

        val lines = fake.commands.map { it.commandLine }
        val prepareAt = lines.indexOfFirst { it.startsWith("rm -rf ") }
        val downloadAt = lines.indexOfFirst { it.startsWith("gh run download") }
        assertTrue(prepareAt >= 0 && downloadAt > prepareAt)
        val runDir = "/sdcard/Download/tianyandev-42"
        val checksumCall = fake.commands.last { it.commandLine.startsWith("sha256sum") }
        assertEquals(runDir, checksumCall.workingDirectory)
        val catCall = fake.commands.last { it.commandLine.startsWith("cat ") }
        assertEquals(runDir, catCall.workingDirectory)
    }

    @Test
    fun `download and checksum use a fresh per-run directory`() = runTest {
        val fake = FakeShellRunner().apply { seedNewRun("feature/x") }
        val final = coordinator(fake).runFast()

        assertTrue(final is BuildStatus.Success)
        val runDir = "/sdcard/Download/tianyandev-42"
        val quoted = "'$runDir'"
        val lines = fake.commands.map { it.commandLine }
        assertEquals("rm -rf $quoted && mkdir -p $quoted", lines.single { it.startsWith("rm ") })
        assertEquals(
            "gh run download 42 -n 'tianyandev-apk' -D $quoted -R 'J09715/Tianyan'",
            lines.single { it.startsWith("gh run download") },
        )
        assertEquals(runDir, fake.commands.single { it.commandLine.startsWith("sha256sum") }.workingDirectory)
        assertEquals(runDir, fake.commands.single { it.commandLine.startsWith("cat ") }.workingDirectory)
        assertEquals("$runDir/TianyanDev-arm64-debug.apk", (final as BuildStatus.Success).apkPath)
    }
}
