package top.tianyan.app.runtime

import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceAtomicWriterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedWriteRetainsOriginalAndRemovesTemporaryFile() {
        val file = temporaryFolder.newFile("script.sh").apply { writeText("original") }
        val result = runCatching {
            WorkspaceAtomicWriter.write(file) { temporary ->
                temporary.writeText("incomplete")
                error("disk full")
            }
        }
        assertTrue(result.isFailure)
        assertEquals("original", file.readText())
        assertEquals(listOf("script.sh"), temporaryFolder.root.listFiles()!!.map { it.name })
    }

    @Test fun replacementRetainsExactPosixPermissions() {
        val file = temporaryFolder.newFile("script.sh")
        assumeTrue("POSIX filesystem required", Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null)
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        WorkspaceAtomicWriter.write(file) { it.writeText("#!/bin/sh\necho done") }
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun writingThroughSymlinkUpdatesTargetAndRetainsLink() {
        val target = temporaryFolder.newFile("target.txt").apply { writeText("original") }
        val link = temporaryFolder.root.resolve("link.txt").toPath()
        val created = runCatching { Files.createSymbolicLink(link, target.toPath()) }
        assumeTrue("Symlink creation unavailable: ${created.exceptionOrNull()?.message}", created.isSuccess)
        WorkspaceAtomicWriter.write(link.toFile()) { it.writeText("updated") }
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("updated", target.readText())
        assertEquals("updated", Files.readString(link))
    }

    @Test fun matchingPreconditionReplacesContent() {
        val file = temporaryFolder.newFile("notes.md").apply { writeText("disk-v1") }
        WorkspaceAtomicWriter.write(file, expectedContent = "disk-v1") { it.writeText("disk-v2") }
        assertEquals("disk-v2", file.readText())
    }

    @Test fun stalePreconditionRetainsOriginalAndRemovesTemporaryFile() {
        val file = temporaryFolder.newFile("notes.md").apply { writeText("external-edit") }
        val result = runCatching {
            WorkspaceAtomicWriter.write(file, expectedContent = "stale-draft") { it.writeText("overwrite") }
        }
        assertTrue(result.exceptionOrNull() is FileWriteConflictException)
        // 冲突时原文件必须保持外部改动，且临时文件不得残留
        assertEquals("external-edit", file.readText())
        assertEquals(listOf("notes.md"), temporaryFolder.root.listFiles()!!.map { it.name })
    }

    @Test fun preconditionOnMissingFileConflicts() {
        val file = temporaryFolder.root.resolve("gone.md")
        val result = runCatching {
            WorkspaceAtomicWriter.write(file, expectedContent = "draft") { it.writeText("overwrite") }
        }
        assertTrue(result.exceptionOrNull() is FileWriteConflictException)
        assertFalse(file.exists())
    }

    @Test fun nullPreconditionSkipsVersionCheck() {
        val file = temporaryFolder.newFile("notes.md").apply { writeText("external-edit") }
        WorkspaceAtomicWriter.write(file, expectedContent = null) { it.writeText("forced") }
        assertEquals("forced", file.readText())
    }
}
