package top.tianyan.app.harness.bot.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.ElfInspector
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.rootfs.RootfsValidator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotMemoryVersionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { it.applicationInfo.nativeLibraryDir = it.cacheDir.absolutePath }
    private val manager = BotMemoryManager(RuntimePathManager(context, RootfsValidator(ElfInspector())), AppLogger(context) { it })
    private val id = UUID.randomUUID().toString()
    private val dir get() = manager.memoryDir(id)
    @After fun clean() = runBlocking { manager.deleteMemory(id) }

    @Test fun `full document editing preserves text beyond tool preview and rejects stale concurrent editors`() = runBlocking {
        manager.initRepo(id, "Test")
        val content = "完整正文\n".repeat(12_000)
        manager.writeMemory(id, "preferences.md", content, "large")
        val opened = manager.readDocument(id, "preferences.md")
        assertEquals(content, opened.content)
        val results = listOf("editor one", "editor two").map { text -> async {
            runCatching { manager.writeMemory(id, opened.path, text, text, opened.revision) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertTrue(manager.readDocument(id, opened.path).content.startsWith("editor "))
        assertEquals(3, manager.getCommitHistory(id).size)
    }

    @Test fun `saving one document does not silently commit unrelated working files`() = runBlocking {
        manager.initRepo(id, "Test")
        File(dir, "knowledge/unrelated.md").writeText("unrelated draft")
        val source = manager.readDocument(id, "preferences.md")
        val saved = manager.writeMemory(id, source.path, "preference", "save preference", source.revision)!!
        assertEquals(listOf("preferences.md"), saved.filesChanged)
        Git.open(dir).use { git ->
            assertTrue(git.status().call().untracked.contains("knowledge/unrelated.md"))
            assertEquals(null, blob(git, saved.hash, "knowledge/unrelated.md"))
        }
    }

    @Test fun `restore preserves dirty ignored and untracked files in reachable backup and can restore them again`() = runBlocking {
        manager.initRepo(id, "Test")
        val old = manager.writeMemory(id, "preferences.md", "old", "old")!!
        val recent = manager.writeMemory(id, "preferences.md", "new", "new")!!
        File(dir, "preferences.md").writeText("dirty current")
        File(dir, ".gitignore").writeText("knowledge/ignored.md\n")
        File(dir, "knowledge/ignored.md").writeText("ignored current")
        File(dir, "knowledge/untracked.md").writeText("untracked current")
        val restored = manager.rollbackToCommit(id, old.hash)
        assertEquals("old", manager.readDocument(id, "preferences.md").content)
        assertFalse(File(dir, "knowledge/untracked.md").exists())
        assertFalse(File(dir, "knowledge/ignored.md").exists())
        Git.open(dir).use { git ->
            assertEquals("dirty current", blob(git, restored.backupHash, "preferences.md"))
            assertEquals("ignored current", blob(git, restored.backupHash, "knowledge/ignored.md"))
            assertEquals("untracked current", blob(git, restored.backupHash, "knowledge/untracked.md"))
            assertEquals(restored.restoredHash, git.repository.resolve("HEAD").name)
            assertTrue(git.status().call().isClean)
        }
        val hashes = manager.getCommitHistory(id).map { it.hash }
        assertTrue(hashes.containsAll(listOf(old.hash, recent.hash, restored.backupHash, restored.restoredHash)))
        manager.rollbackToCommit(id, restored.backupHash)
        assertEquals("dirty current", manager.readDocument(id, "preferences.md").content)
        assertEquals("ignored current", manager.readDocument(id, "knowledge/ignored.md").content)
        assertEquals("untracked current", manager.readDocument(id, "knowledge/untracked.md").content)
    }

    @Test fun `diff shows actual parent changes including deletion and remains read only`() = runBlocking {
        manager.initRepo(id, "Test")
        val initial = manager.getCommitHistory(id).first()
        val added = manager.writeMemory(id, "knowledge/a.md", "before\n", "add")!!
        val modified = manager.writeMemory(id, "knowledge/a.md", "after\n", "edit")!!
        val diff = manager.getCommitDiff(id, modified.hash.take(8))
        assertTrue(diff.text.contains("-before"))
        assertTrue(diff.text.contains("+after"))
        assertFalse(diff.truncated)
        assertEquals(modified.hash, manager.getCommitHistory(id).first().hash)
        assertTrue(manager.getCommitDiff(id, initial.hash).text.contains("persona.md"))
        val restored = manager.rollbackToCommit(id, initial.hash)
        val deletion = manager.getCommitDiff(id, restored.restoredHash)
        assertTrue(deletion.text.contains("deleted file"))
        assertTrue(manager.getCommitHistory(id).first().filesChanged.contains("knowledge/a.md"))
        assertTrue(manager.getCommitHistory(id).any { it.hash == added.hash })
    }

    @Test fun `oversize documents and invalid paths cannot overwrite or escape memory`() = runBlocking {
        manager.initRepo(id, "Test")
        val file = File(dir, "preferences.md")
        file.writeText("x".repeat(1024 * 1024 + 1))
        assertTrue(runCatching { manager.readDocument(id, "preferences.md") }.isFailure)
        assertEquals(1024 * 1024 + 1L, file.length())
        for (path in listOf("../outside.md", "/absolute.md", "nested/.GIT/config.md", ".git/config", "C:/outside.md")) {
            assertTrue(runCatching { manager.readDocument(id, path) }.isFailure)
            assertTrue(runCatching { manager.writeMemory(id, path, "bad", "bad") }.isFailure)
        }
        assertTrue(runCatching { manager.memoryDir("../outside") }.isFailure)
    }

    @Test fun `invalid restore is validated before creating backup or modifying files`() = runBlocking {
        manager.initRepo(id, "Test")
        manager.writeMemory(id, "preferences.md", "keep me", "current")
        val head = manager.getCommitHistory(id).first().hash
        assertTrue(runCatching { manager.rollbackToCommit(id, "0000000") }.isFailure)
        assertEquals(head, manager.getCommitHistory(id).first().hash)
        assertEquals("keep me", manager.readDocument(id, "preferences.md").content)
    }

    @Test fun `restore handles file to directory transitions in both directions`() = runBlocking {
        manager.initRepo(id, "Test")
        val fileVersion = manager.writeMemory(id, "knowledge/node.md", "file contents", "file")!!
        val node = File(dir, "knowledge/node.md")
        check(node.delete()); check(node.mkdir())
        File(node, "child.md").writeText("child contents")
        val directoryVersion = Git.open(dir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit().setMessage("directory").call().name
        }
        manager.rollbackToCommit(id, fileVersion.hash)
        assertTrue(node.isFile)
        assertEquals("file contents", node.readText())
        manager.rollbackToCommit(id, directoryVersion)
        assertTrue(node.isDirectory)
        assertEquals("child contents", File(node, "child.md").readText())
    }

    @Test fun `large diff is explicitly bounded while stored contents remain intact`() = runBlocking {
        manager.initRepo(id, "Test")
        val text = (1..20_000).joinToString("\n") { "unique line $it" }
        val saved = manager.writeMemory(id, "knowledge/large.md", text, "large diff")!!
        val diff = manager.getCommitDiff(id, saved.hash)
        assertTrue(diff.truncated)
        assertTrue(diff.text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        assertEquals(text, manager.readDocument(id, "knowledge/large.md").content)
    }

    private fun blob(git: Git, hash: String, path: String): String? = RevWalk(git.repository).use { walk ->
        val commit = walk.parseCommit(git.repository.resolve(hash))
        TreeWalk.forPath(git.repository, path, commit.tree)?.use { tree ->
            git.repository.open(tree.getObjectId(0)).bytes.toString(Charsets.UTF_8)
        }
    }
}
