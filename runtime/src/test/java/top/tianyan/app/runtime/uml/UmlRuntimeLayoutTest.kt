package top.tianyan.app.runtime.uml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UmlRuntimeLayout 纯逻辑验证。
 *
 * RuntimePathManager 是持有 Android Context 的 Hilt @Singleton，单测里构造不出来；
 * 因此这里不驱动真实布局，而是验证「产物文件存在性 → 缺失判定」这条与文件系统
 * 相关的纯逻辑（canExecute 在 Unix 上跟随文件 +x 位，rootfs 走 isFile），以及常量契约。
 * 真实 UmlRuntimeLayout 的目录拼接走集成/真机路径。
 */
class UmlRuntimeLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 给定一个目录，模拟 UmlRuntimeLayout.missing() 的判定逻辑。 */
    private fun simulateMissing(artifacts: List<File>): List<String> =
        artifacts.mapNotNull { artifact ->
            val ok = if (artifact.name == UmlRuntimeLayout.UML_ROOTFS_NAME) artifact.isFile else artifact.canExecute()
            if (ok) null else artifact.name
        }

    @Test
    fun kernelConstants() {
        assertEquals("libuml.so", UmlRuntimeLayout.UML_KERNEL_NAME)
        assertEquals("libstub.so", UmlRuntimeLayout.UML_STUB_NAME)
        assertEquals("libumnet.so", UmlRuntimeLayout.UML_UMNET_NAME)
        assertEquals("libpasst.so", UmlRuntimeLayout.UML_PASST_NAME)
        assertEquals("rootfs.img", UmlRuntimeLayout.UML_ROOTFS_NAME)
        assertEquals("uml", UmlRuntimeLayout.UML_BASE_NAME)
    }

    @Test
    fun sshPorts() {
        assertEquals(22, UmlRuntimeLayout.GUEST_SSH_PORT)
        assertEquals(2222, UmlRuntimeLayout.HOST_SSH_PORT)
        assertEquals("127.0.0.1", UmlRuntimeLayout.HOST_LOOPBACK)
    }

    @Test
    fun missingDetectsAbsentThenInstalled() {
        val native = tmp.newFolder("native")
        val base = tmp.newFolder("files")

        fun artifacts(native: File, base: File): List<File> = listOf(
            File(native, UmlRuntimeLayout.UML_KERNEL_NAME),
            File(native, UmlRuntimeLayout.UML_STUB_NAME),
            File(native, UmlRuntimeLayout.UML_UMNET_NAME),
            File(native, UmlRuntimeLayout.UML_PASST_NAME),
            File(base, UmlRuntimeLayout.UML_ROOTFS_NAME),
        )

        // 初始全部缺失
        val initial = simulateMissing(artifacts(native, base))
        assertEquals(5, initial.size)

        // 写 4 个二进制（chmod +x 让 canExecute 通过）+ rootfs
        artifacts(native, base).forEach { f ->
            f.writeText("x")
            if (f.name != UmlRuntimeLayout.UML_ROOTFS_NAME) f.setExecutable(true, false)
        }
        val installed = simulateMissing(artifacts(native, base))
        assertTrue(installed.isEmpty())
    }

    @Test
    fun rootfsAloneMissing() {
        val native = tmp.newFolder("native2")
        val base = tmp.newFolder("files2")

        // 只创建 4 个二进制，不建 rootfs
        listOf(UmlRuntimeLayout.UML_KERNEL_NAME, UmlRuntimeLayout.UML_STUB_NAME,
            UmlRuntimeLayout.UML_UMNET_NAME, UmlRuntimeLayout.UML_PASST_NAME).forEach { name ->
            File(native, name).apply { writeText("x"); setExecutable(true, false) }
        }
        val arts = listOf(
            File(native, UmlRuntimeLayout.UML_KERNEL_NAME),
            File(native, UmlRuntimeLayout.UML_STUB_NAME),
            File(native, UmlRuntimeLayout.UML_UMNET_NAME),
            File(native, UmlRuntimeLayout.UML_PASST_NAME),
            File(base, UmlRuntimeLayout.UML_ROOTFS_NAME),
        )
        val missing = simulateMissing(arts)
        assertEquals(listOf(UmlRuntimeLayout.UML_ROOTFS_NAME), missing)
    }
}
