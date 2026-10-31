package top.tianyan.app.core.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `.agents/skills` 标准目录发现（开源社区 Agent Skills 规范）。 */
class AgentSkillScanRootsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `standard roots include attachments workspace and project agents skills`() {
        val attachments = temporaryFolder.newFolder("attachments")
        val workspace = temporaryFolder.newFolder("workspace")
        File(workspace, ".agents/skills/root-skill").mkdirs()
        File(workspace, "proj/.agents/skills/foo").mkdirs()
        File(workspace, "proj/module/.agents/skills/deep").mkdirs()
        File(workspace, "node_modules/.agents/skills/should-skip").mkdirs()

        val guestPaths = AgentSkillRepository.standardScanRoots(attachments, workspace)
            .map { it.guestPrefix }

        assertTrue(guestPaths.contains("/attachments/skills"))
        assertTrue(guestPaths.contains("/workspace/skills"))
        assertTrue(guestPaths.contains("/workspace/.agents/skills"))
        assertTrue(guestPaths.contains("/workspace/proj/.agents/skills"))
        assertTrue(guestPaths.contains("/workspace/proj/module/.agents/skills"))
        assertFalse(guestPaths.any { it.contains("node_modules") })
    }

    @Test
    fun `no workspace directory yields only standard roots`() {
        val attachments = temporaryFolder.newFolder("attachments")
        val guestPaths = AgentSkillRepository.standardScanRoots(attachments, File(attachments, "missing"))
            .map { it.guestPrefix }
        assertTrue(guestPaths.contains("/attachments/skills"))
        assertTrue(guestPaths.contains("/workspace/skills"))
        assertFalse(guestPaths.any { it.contains(".agents") })
    }
}