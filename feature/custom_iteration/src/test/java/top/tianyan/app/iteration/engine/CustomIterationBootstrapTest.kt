package top.tianyan.app.iteration.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CustomIterationBootstrap 纯 JVM 单元测试。
 *
 * 覆盖：空工作区（可被 git clone）、Skill 只落在真实扫描根、工作流缓存位于克隆目标之外、
 * 目录创建失败关闭、资产缺失失败关闭，以及 Fork 插值 / 占位符。
 */
class CustomIterationBootstrapTest {

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory("tianyan-it-$prefix").toFile().apply { deleteOnExit() }

    private val skillBytes = "# tianyan-custom-iteration\n\n真实 Skill 内容\n".toByteArray()

    private val workflowText = """
        name: Build TianyanDev APK
        env:
          TIANYAN_DEV_BUILD: "1"
        packages: 'platform-tools'
    """.trimIndent().toByteArray()

    private fun assets(
        skill: ByteArray? = skillBytes,
        workflow: ByteArray? = workflowText,
    ) = AssetReader { path ->
        when (path) {
            CustomIterationBootstrap.ASSET_SKILL -> skill
            CustomIterationBootstrap.ASSET_WORKFLOW -> workflow
            else -> null
        }
    }

    private fun paths(home: File, attachments: File, workspace: File) =
        CustomIterationPaths(hostHomeDir = home, attachmentsDir = attachments, workspaceDir = workspace)

    @Test
    fun `bootstrap creates an empty workspace under the proot bound home dir`() {
        val home = tempRoot("home")
        val attachments = tempRoot("attachments")
        val ws = tempRoot("ws")

        val result = CustomIterationBootstrap.bootstrap(
            assets = assets(),
            paths = paths(home, attachments, ws),
        )

        assertTrue("bootstrap must succeed: ${result.errorMessage}", result.success)
        val expected = File(home, CustomIterationBootstrap.WORKSPACE_NAME)
        assertTrue("workspace dir must exist at $expected", expected.isDirectory)
        assertEquals(expected.absolutePath, result.workspacePath)
        assertTrue("clone target must stay empty", expected.list().isNullOrEmpty())
        assertFalse(File(expected, ".github").exists())
        assertFalse(File(expected, ".agents").exists())
        assertFalse("legacy .tianyan cache must not be created beside the clone target", File(home, ".tianyan").exists())
        assertNull(result.errorMessage)
        assertTrue(result.prompt.contains("/root/custom_tianyan"))
        assertEquals(
            CustomIterationBootstrap.bootstrapPrompt(CustomIterationBootstrap.GUEST_WORKSPACE, null),
            result.prompt,
        )
    }

    @Test
    fun `skill lands on the real scan roots and workflow stays outside the clone target`() {
        val home = tempRoot("home")
        val attachments = tempRoot("attachments")
        val ws = tempRoot("ws")

        assertTrue(CustomIterationBootstrap.bootstrap(assets(), paths(home, attachments, ws)).success)

        val globalSkill = File(attachments, CustomIterationBootstrap.SKILL_RELATIVE_IN_SCAN_ROOT)
        assertTrue("attachments skill must exist: $globalSkill", globalSkill.isFile)
        assertTrue(globalSkill.readBytes().contentEquals(skillBytes))

        val workspaceRootSkill = File(ws, CustomIterationBootstrap.SKILL_RELATIVE_IN_SCAN_ROOT)
        assertTrue("workspace skill must exist: $workspaceRootSkill", workspaceRootSkill.isFile)
        assertTrue(workspaceRootSkill.readBytes().contentEquals(skillBytes))

        val workflow = File(attachments, CustomIterationBootstrap.WORKFLOW_CACHE_RELATIVE)
        assertTrue("workflow cache must exist: $workflow", workflow.isFile)
        val text = workflow.readText()
        assertTrue(text.contains("packages: 'platform-tools'"))
        assertTrue(text.contains("TIANYAN_DEV_BUILD"))

        val cloneTarget = File(home, CustomIterationBootstrap.WORKSPACE_NAME)
        assertTrue(cloneTarget.list().isNullOrEmpty())
        assertFalse(File(cloneTarget, CustomIterationBootstrap.WORKFLOW_RELATIVE).exists())
        assertFalse(File(cloneTarget, CustomIterationBootstrap.AGENT_SKILL_RELATIVE).exists())
    }

    @Test
    fun `bootstrap leaves an existing clone untouched`() {
        val home = tempRoot("home")
        val attachments = tempRoot("attachments")
        val ws = tempRoot("ws")
        val clone = File(home, CustomIterationBootstrap.WORKSPACE_NAME)
        val marker = File(clone, "README.md")
        marker.parentFile?.mkdirs()
        marker.writeText("already cloned")

        val result = CustomIterationBootstrap.bootstrap(assets(), paths(home, attachments, ws))

        assertTrue(result.success)
        assertEquals("already cloned", marker.readText())
        assertFalse(File(clone, ".github").exists())
        assertFalse(File(clone, ".agents").exists())
    }

    @Test
    fun `bootstrap is idempotent and refreshes changed skill content`() {
        val home = tempRoot("home")
        val attachments = tempRoot("attachments")
        val ws = tempRoot("ws")
        val p = paths(home, attachments, ws)

        assertTrue(CustomIterationBootstrap.bootstrap(assets(), p).success)

        val globalSkill = File(attachments, CustomIterationBootstrap.SKILL_RELATIVE_IN_SCAN_ROOT)
        val stamp = globalSkill.lastModified()

        assertTrue(CustomIterationBootstrap.bootstrap(assets(), p).success)
        assertEquals(stamp, globalSkill.lastModified())

        globalSkill.writeText("tampered")
        assertTrue(CustomIterationBootstrap.bootstrap(assets(), p).success)
        assertTrue(globalSkill.readBytes().contentEquals(skillBytes))
        assertTrue(File(home, CustomIterationBootstrap.WORKSPACE_NAME).list().isNullOrEmpty())
    }

    @Test
    fun `missing skill asset fails closed and does not hand over the prompt`() {
        val home = tempRoot("home")
        val result = CustomIterationBootstrap.bootstrap(
            assets = assets(skill = null),
            paths = paths(home, tempRoot("attachments"), tempRoot("ws")),
        )

        assertFalse(result.success)
        assertEquals("", result.workspacePath)
        assertEquals("", result.prompt)
        assertTrue(result.errorMessage?.contains("缺少内置资产") == true)
        assertTrue(result.errorMessage?.contains(CustomIterationBootstrap.ASSET_SKILL) == true)
    }

    @Test
    fun `workflow without the build marker is rejected`() {
        val result = CustomIterationBootstrap.bootstrap(
            assets = assets(workflow = "name: not-the-dev-workflow\n".toByteArray()),
            paths = paths(tempRoot("home"), tempRoot("attachments"), tempRoot("ws")),
        )

        assertFalse(result.success)
        assertEquals("", result.prompt)
        assertTrue(result.errorMessage?.contains("TIANYAN_DEV_BUILD") == true)
    }

    @Test
    fun `unwritable skill location fails closed instead of pretending success`() {
        val attachments = tempRoot("attachments")
        File(attachments, "skills").writeText("blocker")

        val result = CustomIterationBootstrap.bootstrap(
            assets(),
            paths(tempRoot("home"), attachments, tempRoot("ws")),
        )

        assertFalse(result.success)
        assertEquals("", result.prompt)
        assertTrue(result.errorMessage?.contains("缺少内置资产") == true)
        assertTrue(result.errorMessage.orEmpty().contains(File(attachments, "skills").absolutePath))
    }

    @Test
    fun `workspace blocked by a file reports the absolute path and skips the prompt`() {
        val home = tempRoot("home")
        val blocked = File(home, CustomIterationBootstrap.WORKSPACE_NAME)
        blocked.writeText("not-a-directory")

        val result = CustomIterationBootstrap.bootstrap(
            assets(),
            paths(home, tempRoot("attachments"), tempRoot("ws")),
        )

        assertFalse(result.success)
        assertEquals("", result.workspacePath)
        assertEquals("", result.prompt)
        assertTrue(
            "错误信息应包含被占用路径的绝对路径",
            result.errorMessage.orEmpty().contains(blocked.absolutePath),
        )
    }

    @Test
    fun `prompt carries the fork repo and stays renderable`() {
        val prompt = CustomIterationBootstrap.bootstrapPrompt(
            guestWorkspace = CustomIterationBootstrap.GUEST_WORKSPACE,
            forkRepo = "haotaoran520/tianyan",
        )
        assertTrue(prompt.contains("构建目标仓库 = haotaoran520/tianyan"))
        assertTrue(prompt.contains("https://github.com/J09715/Tianyan"))
        assertTrue(prompt.contains(".github/workflows/tianyandev-build.yml"))
        assertTrue(prompt.contains(CustomIterationBootstrap.GUEST_WORKFLOW_CACHE))
        assertFalse(prompt.contains("构建目标仓库 = J09715/Tianyan"))
        assertFalse(prompt.contains("\${"))
        assertFalse(prompt.contains("OFFICIAL_REPO_SLUG"))
        assertFalse(prompt.contains("WORKSPACE_NAME"))
    }

    @Test
    fun `missing fork becomes a placeholder that forces reading git remote`() {
        val prompt = CustomIterationBootstrap.bootstrapPrompt(
            guestWorkspace = CustomIterationBootstrap.GUEST_WORKSPACE,
            forkRepo = null,
        )
        assertTrue(prompt.contains(CustomIterationBootstrap.FORK_REPO_PLACEHOLDER))
        assertTrue(prompt.contains("git remote get-url origin"))
        assertTrue(prompt.contains("禁止对 J09715/Tianyan 执行 workflow_dispatch"))
        assertFalse(prompt.contains("构建目标仓库 = J09715/Tianyan"))
        assertEquals(
            CustomIterationBootstrap.FORK_REPO_PLACEHOLDER,
            CustomIterationBootstrap.promptForkLabel("  "),
        )
        assertEquals(
            CustomIterationBootstrap.FORK_REPO_PLACEHOLDER,
            CustomIterationBootstrap.promptForkLabel("not a repo"),
        )
    }
}
