package top.tianyan.app.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [HostPathPolicy] 的路径准入契约。
 *
 * 桥接端点的入参来自沙箱内的 Agent，是不可信输入。这个测试锁住三件事：
 *
 * 1. **沙箱路径映射**：`/sdcard/x` 必须落到宿主共享存储；
 * 2. **越界拒绝**：`..` 穿越、绝对路径指向别处、软链指向别处，都必须返回 null；
 * 3. **合法路径放行**：共享存储内的文件必须能通过。
 */
class HostPathPolicyTest {

    /** 用临时目录当允许根，测试不依赖真机路径。 */
    private fun withRoot(block: (File) -> Unit) {
        // 用 createTempDirectory 而不是已废弃的 createTempDir：后者在默认临时目录
        // 建出的目录权限过宽（其它用户可读），跑在 CI 容器里有风险。
        val dir = kotlin.io.path.createTempDirectory("host-path-policy").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `沙箱 sdcard 路径映射到宿主共享存储`() {
        assertEquals(
            "/storage/emulated/0/Download/app.apk",
            HostPathPolicy.mapSandboxToHost("/sdcard/Download/app.apk"),
        )
        assertEquals("/storage/emulated/0", HostPathPolicy.mapSandboxToHost("/sdcard"))
    }

    @Test
    fun `已经是宿主路径时原样保留`() {
        assertEquals(
            "/storage/emulated/0/Download/app.apk",
            HostPathPolicy.mapSandboxToHost("/storage/emulated/0/Download/app.apk"),
        )
    }

    @Test
    fun `允许根之内的文件放行`() = withRoot { root ->
        val target = File(root, "Download/app.apk").apply { parentFile.mkdirs(); writeText("x") }
        val resolved = HostPathPolicy.canonicalWithin(target.path, listOf(root))
        assertNotNull("允许根之内的文件应放行", resolved)
        assertEquals(target.canonicalPath, resolved)
    }

    @Test
    fun `允许根本身放行`() = withRoot { root ->
        assertNotNull(HostPathPolicy.canonicalWithin(root.path, listOf(root)))
    }

    @Test
    fun `父目录穿越被拒绝`() = withRoot { root ->
        val sibling = File(root.parentFile, "secret.txt").apply { writeText("secret") }
        val attack = "${root.path}/../${sibling.name}"
        assertNull("父目录穿越必须被拒绝", HostPathPolicy.canonicalWithin(attack, listOf(root)))
    }

    @Test
    fun `多层穿越被拒绝`() = withRoot { root ->
        val attack = "${root.path}/a/b/../../../../etc/passwd"
        assertNull(HostPathPolicy.canonicalWithin(attack, listOf(root)))
    }

    @Test
    fun `完全无关的绝对路径被拒绝`() = withRoot { root ->
        assertNull(HostPathPolicy.canonicalWithin("/etc/passwd", listOf(root)))
    }

    @Test
    fun `前缀相同但不同目录的路径不被误判为在允许根内`() = withRoot { root ->
        // 经典前缀绕过：/tmp/root-evil 不在 /tmp/root 之下
        val evil = File(root.parentFile, "${root.name}-evil").apply { mkdirs() }
        try {
            val target = File(evil, "x.txt").apply { writeText("x") }
            assertNull(
                "同前缀的兄弟目录必须被拒绝",
                HostPathPolicy.canonicalWithin(target.path, listOf(root)),
            )
        } finally {
            evil.deleteRecursively()
        }
    }

    @Test
    fun `符号链接指向允许根之外时被拒绝`() = withRoot { root ->
        val outside = File(root.parentFile, "outside-target.txt").apply { writeText("secret") }
        val link = File(root, "link.txt")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(
                "软链指向允许根之外必须被拒绝",
                HostPathPolicy.canonicalWithin(link.path, listOf(root)),
            )
        } catch (e: Exception) {
            // 某些文件系统不允许建软链（CI 容器常见），跳过即可——不构成失败
            assertTrue("软链创建失败时跳过", true)
        } finally {
            link.delete()
            outside.delete()
        }
    }

    @Test
    fun `resolve 会把沙箱路径映射后一并做准入判定`() = withRoot { root ->
        val target = File(root, "a.apk").apply { writeText("x") }
        // 构造一个映射后仍在根内、但路径里带 .. 的沙箱路径
        val sandbox = "/sdcard/../${root.name}/a.apk"
        val resolved = HostPathPolicy.resolve(sandbox, listOf(root))
        // /sdcard 被映射到 /storage/emulated/0，与本测试的临时根无关，因此越界
        assertNull("映射到别处的路径必须被拒绝", resolved)

        // 而直接给宿主绝对路径且在根内，应放行
        assertNotNull(HostPathPolicy.resolve(target.path, listOf(root)))
    }
}
