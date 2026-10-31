package top.tianyan.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryArtifactPathsTest {

    private val root = File("/data/app/files/linux-runtime/workspace")

    @Test
    fun `project name is extracted from sandbox workspace paths`() {
        assertEquals("proj-1", DeliveryArtifactPaths.projectName("/workspace/proj-1"))
        assertEquals("proj-1", DeliveryArtifactPaths.projectName("/workspace/proj-1/"))
        assertEquals("proj-1", DeliveryArtifactPaths.projectName("\\workspace\\proj-1"))
        assertEquals("proj-1", DeliveryArtifactPaths.projectName("proj-1"))
        // 项目本身叫 workspace 是允许的；裸 "workspace" 则按根目录处理，不猜成项目名。
        assertEquals("workspace", DeliveryArtifactPaths.projectName("/workspace/workspace"))
        assertNull(DeliveryArtifactPaths.projectName("workspace"))
        assertNull(DeliveryArtifactPaths.projectName(""))
        assertNull(DeliveryArtifactPaths.projectName("/workspace/"))
        assertNull(DeliveryArtifactPaths.projectName("/workspace/.."))
        assertNull(DeliveryArtifactPaths.projectName("/workspace/."))
    }

    @Test
    fun `relative artifact path resolves under the project directory`() {
        val file = DeliveryArtifactPaths.hostFile("/workspace/proj-1", "app/build/outputs/apk/release/app.apk", root)
        assertEquals(File(root, "proj-1/app/build/outputs/apk/release/app.apk"), file)

        val log = DeliveryArtifactPaths.hostFile("/workspace/proj-1", ".tianyan-outputs/run-1.log", root)
        assertEquals(File(root, "proj-1/.tianyan-outputs/run-1.log"), log)
    }

    @Test
    fun `escaping and malformed relative paths are rejected`() {
        assertNull(DeliveryArtifactPaths.hostFile("/workspace/proj-1", "../other/secret.txt", root))
        assertNull(DeliveryArtifactPaths.hostFile("/workspace/proj-1", "a//b.txt", root))
        assertNull(DeliveryArtifactPaths.hostFile("/workspace/proj-1", "./b.txt", root))
        assertNull(DeliveryArtifactPaths.hostFile("/workspace/proj-1", "", root))
        assertNull(DeliveryArtifactPaths.hostFile("", "a.txt", root))
        assertNull(DeliveryArtifactPaths.hostFile("/workspace/proj-1", "a.txt", workspaceRoot = null))
    }

    @Test
    fun `leading slash on the relative path is normalised`() {
        assertEquals(File(root, "proj-1/report.pdf"), DeliveryArtifactPaths.hostFile("/workspace/proj-1", "/report.pdf", root))
    }
}