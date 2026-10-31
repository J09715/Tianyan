package top.tianyan.app.core.tools.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Unit tests for BackupArchive path validation — no Android/Room dependencies. */
class LocalBackupArchiveTest {

    @Test fun `safePath rejects directory traversal`() {
        listOf("../etc/passwd", "foo/../bar", "a/../../b").forEach { path ->
            assertThrows("Expected exception for: $path", IllegalArgumentException::class.java) {
                BackupArchive.safePath(path)
            }
        }
    }

    @Test fun `safePath rejects absolute paths`() {
        listOf("/etc/passwd", "/data/data/app/file.txt").forEach { path ->
            assertThrows("Expected exception for: $path", IllegalArgumentException::class.java) {
                BackupArchive.safePath(path)
            }
        }
    }

    @Test fun `safePath rejects null byte`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.safePath("foo\u0000bar")
        }
    }

    @Test fun `safePath accepts valid relative paths`() {
        listOf(
            "attachments/img.png",
            "skills/abc/file.sh",
            "harness_blobs/deadbeef.json",
        ).forEach { path ->
            BackupArchive.safePath(path) // must not throw
        }
    }

    @Test fun `digest is deterministic and hex-encoded`() {
        val tmp = Files.createTempFile("backup-test", ".dat").toFile()
        try {
            tmp.writeText("hello backup")
            val d1 = BackupArchive.digest(tmp)
            val d2 = BackupArchive.digest(tmp)
            assertEquals(d1, d2)
            assertTrue(d1.matches(Regex("[0-9a-f]{64}")))
        } finally { tmp.delete() }
    }
}
