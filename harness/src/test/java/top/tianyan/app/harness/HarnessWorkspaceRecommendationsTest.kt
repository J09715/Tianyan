package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessWorkspaceRecommendationsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `resolves workspace root and existing project`() {
        val root = temporary.newFolder("workspace")
        val project = root.resolve("project").apply { mkdir() }
        assertEquals(root.canonicalFile, HarnessWorkspaceRecommendations.resolveDirectory(root, "/workspace"))
        assertEquals(root.canonicalFile, HarnessWorkspaceRecommendations.resolveDirectory(root, "/workspace/"))
        assertEquals(project.canonicalFile, HarnessWorkspaceRecommendations.resolveDirectory(root, "/workspace/project"))
        assertNull(HarnessWorkspaceRecommendations.resolveDirectory(root, "/workspace/missing"))
    }

    @Test
    fun `rejects traversal and prefix confusion even when target exists`() {
        val root = temporary.newFolder("workspace")
        temporary.newFolder("workspace-secret")
        for (path in listOf("/workspace/..", "/workspace/../workspace-secret", "/workspace-secret", "/etc", "", "project")) {
            assertNull(path, HarnessWorkspaceRecommendations.resolveDirectory(root, path))
        }
    }
}
