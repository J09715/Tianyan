package top.tianyan.app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLoginEnvironmentTest {
    @Test
    fun profileGuardRunsBeforeIdAndEarlyProfileSortsBeforeDebuginfod() {
        val root = Files.createTempDirectory("tianyan-rootfs").toFile()
        try {
            val etc = File(root, "etc")
            etc.mkdirs()
            val original = buildString {
                repeat(11) { append("# comment\n") }
                append("if [ \"\$(id -u)\" -eq 0 ]; then\n")
                append("  PATH=/usr/bin\n")
                append("fi\n")
            }
            File(etc, "profile").writeText(original)
            File(etc, "bash.bashrc").writeText("# bashrc\n[ -z \"\$PS1\" ] && return\n")
            GuestLoginEnvironment.install(root)

            val profile = File(etc, "profile").readText()
            assertTrue(profile.startsWith(GuestLoginEnvironment.MARKER))
            assertTrue(profile.indexOf("export PATH") < profile.indexOf("id -u"))
            assertTrue(profile.contains(original))
            assertFalse(GuestLoginEnvironment.preamble().contains("id"))
            assertFalse(GuestLoginEnvironment.preamble().contains("tr"))

            val bashrc = File(etc, "bash.bashrc").readText()
            assertTrue(bashrc.startsWith(GuestLoginEnvironment.MARKER))
            assertTrue(bashrc.contains("# bashrc"))

            val early = File(etc, "profile.d/${GuestLoginEnvironment.EARLY_PROFILE}")
            val legacy = File(etc, "profile.d/${GuestLoginEnvironment.PROFILE}")
            assertEquals(GuestLoginEnvironment.profileScript(), early.readText())
            assertEquals(early.readText(), legacy.readText())
            assertTrue(early.readText().contains("export PATH=${EnvironmentResolver().runtimePath()}"))
            val names = listOf("debuginfod.sh", GuestLoginEnvironment.PROFILE, GuestLoginEnvironment.EARLY_PROFILE).sorted()
            assertEquals(GuestLoginEnvironment.EARLY_PROFILE, names.first())

            val once = File(etc, "profile").readText()
            GuestLoginEnvironment.install(root)
            assertEquals(once, File(etc, "profile").readText())
            assertEquals(1, GuestLoginEnvironment.MARKER.toRegex().findAll(once).count())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingProfileIsCreatedAndSymlinkIsReplacedWithGuardedFile() {
        // Windows 默认不允许普通进程创建符号链接（需开发者模式/管理员）：
        // 跳过而不是把环境限制当作失败。Linux CI 仍然覆盖这条分支。
        requireSymlinkSupport()
        val root = Files.createTempDirectory("tianyan-rootfs-link").toFile()
        try {
            GuestLoginEnvironment.install(root)
            val profile = File(root, "etc/profile")
            assertEquals(GuestLoginEnvironment.preamble(), profile.readText())

            val target = File(root, "etc/profile.real")
            target.writeText("# vendor\nif [ \"\$(id -u)\" -eq 0 ]; then\n:\nfi\n")
            profile.delete()
            Files.createSymbolicLink(profile.toPath(), target.toPath())
            GuestLoginEnvironment.install(root)
            assertFalse(Files.isSymbolicLink(profile.toPath()))
            val text = profile.readText()
            assertTrue(text.startsWith(GuestLoginEnvironment.MARKER))
            assertTrue(text.contains("# vendor"))
            assertEquals("# vendor\nif [ \"\$(id -u)\" -eq 0 ]; then\n:\nfi\n", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun requireSymlinkSupport() {
        val probe = Files.createTempDirectory("tianyan-symlink-probe").toFile()
        try {
            Files.createSymbolicLink(File(probe, "link").toPath(), File(probe, "target").toPath())
        } catch (error: Exception) {
            org.junit.Assume.assumeNoException("当前文件系统不允许创建符号链接", error)
        } finally {
            probe.deleteRecursively()
        }
    }

    @Test
    fun userEditedGuardIsNotDuplicated() {
        val edited = GuestLoginEnvironment.MARKER + "\nPATH=/custom\n# edited\n"
        assertEquals(edited, GuestLoginEnvironment.merge(edited))
    }
}
