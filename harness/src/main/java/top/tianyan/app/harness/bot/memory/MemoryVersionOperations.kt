package top.tianyan.app.harness.bot.memory

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import top.tianyan.app.core.model.bot.BotMemoryDiff
import top.tianyan.app.core.model.bot.BotMemoryRestore

/** Called under the manager's per-Bot lock. No history rewrite, shell Git or external processes. */
internal object MemoryVersionOperations {
    fun commit(git: Git, hash: String): RevCommit {
        require(hash.matches(Regex("[0-9a-fA-F]{7,40}"))) { "无效的提交哈希" }
        return RevWalk(git.repository).use { walk ->
            val target = walk.parseCommit(requireNotNull(git.repository.resolve(hash)) { "提交不存在或哈希不唯一" })
            val head = walk.parseCommit(git.repository.resolve("HEAD"))
            require(walk.isMergedInto(target, head)) { "只能查看或恢复当前记忆历史中的提交" }
            target
        }
    }

    fun diff(dir: File, hash: String): BotMemoryDiff = Git.open(dir).use { git ->
        val target = commit(git, hash)
        val output = BoundedOutput(64 * 1024)
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setContext(3)
            RevWalk(git.repository).use { walk ->
                val parent = if (target.parentCount == 0) null else walk.parseCommit(target.getParent(0).id).tree
                formatter.format(parent, target.tree)
                formatter.flush()
            }
        }
        BotMemoryDiff(target.name, output.content.toString(Charsets.UTF_8.name()), output.truncated)
    }

    fun restore(dir: File, hash: String): BotMemoryRestore = Git.open(dir).use { git ->
        // Validate all target paths and payloads before changing either history or working files.
        val target = commit(git, hash)
        val wanted = tree(git, target)
        var currentBytes = 0
        val existing = dir.walkTopDown().onEnter {
            if (it == dir) true else if (it == File(dir, ".git")) false else {
                MemoryFileAccess.resolve(dir, it.relativeTo(dir).invariantSeparatorsPath); true
            }
        }.filter { !it.isDirectory }.take(257).map { file ->
            val path = file.relativeTo(dir).invariantSeparatorsPath
            MemoryFileAccess.resolve(dir, path)
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "不支持恢复含符号链接的记忆库" }
            val bytes = MemoryFileAccess.bytes(file)
            currentBytes += bytes.size
            require(currentBytes <= 16 * 1024 * 1024) { "当前记忆总量过大，未开始恢复" }
            path to bytes
        }.toMap()
        require(existing.size <= 256) { "记忆文件过多，未开始恢复" }
        val author = PersonIdent(git.repository)
        val backup = commitTree(git, existing, author, "backup: 恢复 ${target.name.take(8)} 前的记忆快照")
        (existing.keys - wanted.keys).forEach { path -> Files.deleteIfExists(MemoryFileAccess.resolve(dir, path).toPath()) }
        dir.walkBottomUp().onEnter { it != File(dir, ".git") }.filter {
            it != dir && it.isDirectory && it.list()?.isEmpty() == true
        }.forEach { Files.deleteIfExists(MemoryFileAccess.resolve(dir, it.relativeTo(dir).invariantSeparatorsPath).toPath()) }
        wanted.forEach { (path, bytes) -> MemoryFileAccess.write(MemoryFileAccess.resolve(dir, path), bytes) }
        val restored = commitTree(git, wanted, author, "restore: 恢复记忆到 ${target.name.take(8)}；备份 ${backup.name.take(8)}")
        BotMemoryRestore(backup.name, restored.name)
    }

    /** Build the exact snapshot, including ignored files, without changing or rewriting older commits. */
    private fun commitTree(git: Git, files: Map<String, ByteArray>, author: PersonIdent, message: String): ObjectId {
        val repository = git.repository
        return repository.newObjectInserter().use { inserter ->
            val cache = DirCache.newInCore()
            val builder = cache.builder()
            val ordered = files.keys.sortedWith { a, b ->
                val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
                var comparison = 0
                for (index in 0 until minOf(left.size, right.size)) {
                    comparison = (left[index].toInt() and 255) - (right[index].toInt() and 255)
                    if (comparison != 0) break
                }
                if (comparison == 0) left.size - right.size else comparison
            }
            ordered.forEach { path ->
                builder.add(DirCacheEntry(path).apply {
                    fileMode = FileMode.REGULAR_FILE
                    setObjectId(inserter.insert(Constants.OBJ_BLOB, files.getValue(path)))
                })
            }
            builder.finish()
            val parent = requireNotNull(repository.resolve("HEAD"))
            val commit = CommitBuilder().apply {
                setTreeId(cache.writeTree(inserter)); setParentId(parent)
                setAuthor(author); setCommitter(author); setMessage(message)
            }
            val id = inserter.insert(commit)
            inserter.flush()
            val update = repository.updateRef("HEAD").apply { setExpectedOldObjectId(parent); setNewObjectId(id); setRefLogMessage(message, false) }
            check(update.update() in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD)) { "记忆历史已变化，快照未覆盖其他提交" }
            val disk = repository.lockDirCache()
            try {
                val index = disk.builder()
                for (entry in 0 until cache.entryCount) index.add(DirCacheEntry(cache.getEntry(entry)))
                check(index.commit()) { "快照已保留，但 Git 索引更新失败" }
            } finally { disk.unlock() }
            id
        }
    }

    private fun tree(git: Git, target: RevCommit): Map<String, ByteArray> = TreeWalk(git.repository).use { walk ->
        walk.addTree(target.tree); walk.isRecursive = true
        var total = 0
        buildMap {
            while (walk.next()) {
                require(size < 256 && walk.getFileMode(0) == FileMode.REGULAR_FILE) { "记忆树包含过多文件或不支持的文件类型" }
                val path = walk.pathString
                MemoryFileAccess.resolve(git.repository.workTree, path)
                val blob = git.repository.open(walk.getObjectId(0))
                require(blob.size <= MemoryFileAccess.MAX_BYTES) { "历史记忆文件超过 1 MiB，未开始恢复" }
                val bytes = blob.getBytes(MemoryFileAccess.MAX_BYTES)
                total += bytes.size
                require(total <= 16 * 1024 * 1024) { "历史记忆总量过大，未开始恢复" }
                put(path, bytes)
            }
        }
    }

    private class BoundedOutput(private val limit: Int) : OutputStream() {
        val content = ByteArrayOutputStream()
        var truncated = false
        override fun write(value: Int) { if (content.size() < limit) content.write(value) else truncated = true }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val count = minOf(length, limit - content.size())
            if (count > 0) content.write(bytes, offset, count)
            if (count < length) truncated = true
        }
    }
}
