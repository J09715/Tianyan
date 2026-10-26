package top.tianyan.app.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceExplorerNavigationTest {

    @Test
    fun `computeExplorerPath preserves subdirectory when returning to same project without explicit path`() {
        val currentProject = "tianyan_app"
        val currentPath = "app/src/main/java"

        // Returning from CodeEditor with initialPath = ""
        val nextPath = computeExplorerPath(
            currentProject = currentProject,
            currentPath = currentPath,
            targetProject = "tianyan_app",
            targetPath = "",
        )

        assertEquals("Same project with empty targetPath should keep current subdirectory", "app/src/main/java", nextPath)
    }

    @Test
    fun `computeExplorerPath resets to root when target is empty and currentPath is root`() {
        val nextPath = computeExplorerPath(
            currentProject = "tianyan_app",
            currentPath = "",
            targetProject = "tianyan_app",
            targetPath = "",
        )

        assertEquals("", nextPath)
    }

    @Test
    fun `computeExplorerPath respects explicit non-empty target path in same project`() {
        val currentProject = "tianyan_app"
        val currentPath = "app/src/main/java"

        val nextPath = computeExplorerPath(
            currentProject = currentProject,
            currentPath = currentPath,
            targetProject = "tianyan_app",
            targetPath = "/build/outputs",
        )

        assertEquals("build/outputs", nextPath)
    }

    @Test
    fun `computeExplorerPath resets to target path when switching to different project`() {
        val currentProject = "old_project"
        val currentPath = "src/models"

        val nextPath = computeExplorerPath(
            currentProject = currentProject,
            currentPath = currentPath,
            targetProject = "new_project",
            targetPath = "",
        )

        assertEquals("Switching projects should reset to target path", "", nextPath)
    }

    @Test
    fun `computeExplorerPath initializes path when currentProject is null`() {
        val nextPath = computeExplorerPath(
            currentProject = null,
            currentPath = "",
            targetProject = "first_project",
            targetPath = "/docs",
        )

        assertEquals("docs", nextPath)
    }

    @Test
    fun `isValidWorkspaceEntryName validates entry names correctly`() {
        assertTrue(isValidWorkspaceEntryName("main.kt"))
        assertTrue(isValidWorkspaceEntryName("MyClass.java"))
        assertTrue(isValidWorkspaceEntryName("build-config.json"))

        assertFalse(isValidWorkspaceEntryName(""))
        assertFalse(isValidWorkspaceEntryName(" leadingSpace"))
        assertFalse(isValidWorkspaceEntryName("trailingSpace "))
        assertFalse(isValidWorkspaceEntryName(".git"))
        assertFalse(isValidWorkspaceEntryName("."))
        assertFalse(isValidWorkspaceEntryName(".."))
        assertFalse(isValidWorkspaceEntryName("sub/folder"))
        assertFalse(isValidWorkspaceEntryName("sub\\folder"))
    }
}
