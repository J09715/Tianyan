package top.tianyan.app.harness.bot.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.bot.BotMemoryFile
import top.tianyan.app.core.model.bot.MemoryCommit
import top.tianyan.app.runtime.RuntimePathManager
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Bot 专属 Git 记忆仓库。
 *
 * 仓库位于宿主应用私有目录（runtime base/bots/<id>/memory），由 JGit 驱动；
 * 不依赖 PRoot 内是否安装 git，也不会把记忆暴露给其它 Bot 的工作区。
 */
class BotMemoryManager(
    private val pathManager: RuntimePathManager,
    private val logger: AppLogger,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withMemory(botId: String, block: () -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut(botId) { Mutex() }.withLock { block() }
    }

    fun memoryDir(botId: String): File {
        require(botId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "非法 Bot ID" }
        val bots = File(pathManager.baseDir, "bots")
        val bot = File(bots, botId)
        val memory = File(bot, "memory")
        require(listOf(bots, bot, memory).none { Files.isSymbolicLink(it.toPath()) }) { "Bot 记忆根目录不能是符号链接" }
        return memory
    }

    suspend fun initRepo(botId: String, botName: String) = withMemory(botId) {
        val dir = memoryDir(botId)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 Bot 记忆目录: $dir")
        cleanStaleLock(dir)
        val alreadyInitialized = RepositoryCacheAccess.hasRepository(dir)
        Git.init().setDirectory(dir).call().use { git ->
            configureIdentity(git, botName, botId)
            if (!alreadyInitialized || !hasCommits(git.repository)) {
                writeSeedFiles(dir)
                commitAll(git, botName, "chore: 初始化 Bot 记忆库")
            }
        }
    }

    suspend fun writeMemory(
        botId: String,
        relativePath: String,
        content: String,
        commitMessage: String,
        expectedRevision: String? = null,
    ): MemoryCommit? = withMemory(botId) {
        val dir = memoryDir(botId)
        val target = resolveSafe(dir, relativePath)
        require(target.extension.lowercase() == "md") { "记忆文件仅允许 Markdown: $relativePath" }
        if (expectedRevision != null) require(MemoryFileAccess.read(dir, relativePath).revision == expectedRevision) {
            "记忆已被其他执行修改；草稿已保留，请重新载入后合并，未覆盖当前内容"
        }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_MEMORY_BYTES) { "单个记忆文件不能超过 1 MiB" }
        ensureRepo(dir, botId)
        MemoryFileAccess.write(target, content.toByteArray(Charsets.UTF_8))
        cleanStaleLock(dir)
        Git.open(dir).use { git ->
            val identity = git.repository.config.getString("user", null, "name") ?: "Bot $botId"
            val gitPath = target.relativeTo(dir).invariantSeparatorsPath
            git.add().addFilepattern(gitPath).call()
            val commit = if (git.status().addPath(gitPath).call().isClean) null else git.commit()
                .setOnly(gitPath).setMessage(commitMessage).setAuthor(identity, "bot-$botId@tianyan.local")
                .setCommitter(identity, "bot-$botId@tianyan.local").call()
            commit?.let { toMemoryCommit(git.repository, it, listOf(relativePath)) }
        }
    }

    suspend fun readDocument(botId: String, path: String) = withMemory(botId) { MemoryFileAccess.read(memoryDir(botId), path) }

    suspend fun getCommitDiff(botId: String, hash: String) = withMemory(botId) { MemoryVersionOperations.diff(memoryDir(botId), hash) }

    suspend fun readMemory(botId: String, relativePath: String): String = withMemory(botId) {
        val dir = memoryDir(botId)
        val target = resolveSafe(dir, relativePath)
        if (target.isFile) readBounded(target, MAX_TOOL_CHARS) else ""
    }

    suspend fun listFiles(botId: String): List<BotMemoryFile> = withMemory(botId) {
        val dir = memoryDir(botId)
        if (!dir.isDirectory) return@withMemory emptyList()
        dir.walkTopDown()
            .filter { it.isFile && !it.path.contains("${File.separator}.git${File.separator}") && it.name != ".git" }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .map { BotMemoryFile(it.relativeTo(dir).invariantSeparatorsPath, it.length()) }
            .toList()
    }

    /** 供系统提示注入的人格与长期记忆；字符预算由插件上下文统一裁剪。 */
    suspend fun assembleContext(botId: String): String = withMemory(botId) {
        val dir = memoryDir(botId)
        if (!dir.isDirectory) return@withMemory ""
        buildString {
            appendFileSection("【Bot 核心人格与设定】", File(dir, PERSONA), 1_600)
            appendFileSection("【用户长效偏好记忆】", File(dir, PREFERENCES), 1_200)
            appendFileSection("【历史工作备忘笔记】", File(dir, DIALOGUE_NOTES), 1_200)
            val knowledgeDir = File(dir, "knowledge")
            val knowledge = knowledgeDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "md" }
                ?.sortedBy { it.name }
                ?.take(MAX_KNOWLEDGE_FILES)
                .orEmpty()
            if (knowledge.isNotEmpty()) {
                appendLine("【领域知识摘要】")
                knowledge.forEach { file ->
                    appendLine("### ${file.name}")
                    appendLine(readBounded(file, MAX_KNOWLEDGE_CHARS))
                    appendLine()
                }
            }
        }.take(MAX_CONTEXT_CHARS)
    }

    suspend fun getCommitHistory(botId: String, limit: Int = 30): List<MemoryCommit> = withMemory(botId) {
        val dir = memoryDir(botId)
        if (!RepositoryCacheAccess.hasRepository(dir)) return@withMemory emptyList()
        Git.open(dir).use { git -> git.log().setMaxCount(limit).call().map { toMemoryCommit(git.repository, it) } }
    }

    suspend fun rollbackToCommit(botId: String, hash: String) = withMemory(botId) {
        val dir = memoryDir(botId)
        cleanStaleLock(dir)
        MemoryVersionOperations.restore(dir, hash)
    }

    suspend fun deleteMemory(botId: String) = withMemory(botId) {
        val dir = memoryDir(botId)
        if (dir.exists() && !dir.deleteRecursively()) logger.w("[BotMemoryManager] 记忆目录删除不完整: $dir")
    }

    suspend fun replaceMemoryTree(botId: String, source: File, botName: String) = withMemory(botId) {
        require(source.isDirectory) { "导入包缺少 memory/ 目录" }
        val dir = memoryDir(botId)
        if (dir.exists()) dir.deleteRecursively()
        copyTreeSafely(source, dir)
        cleanStaleLock(dir)
        if (!RepositoryCacheAccess.hasRepository(dir)) {
            Git.init().setDirectory(dir).call().use { git ->
                configureIdentity(git, botName, botId)
                writeSeedFiles(dir)
                commitAll(git, botName, "chore: 导入 Bot 记忆库")
            }
        } else {
            Git.open(dir).use { git -> configureIdentity(git, botName, botId) }
        }
    }

    suspend fun exportMemoryTree(botId: String, dest: File) = withMemory(botId) {
        val dir = memoryDir(botId)
        require(dir.isDirectory) { "Bot 记忆仓库不存在" }
        copyTreeSafely(dir, dest, skipGitLocks = true)
    }

    private fun configureIdentity(git: Git, botName: String, botId: String) {
        val config = git.repository.config
        config.setString("user", null, "name", botName)
        config.setString("user", null, "email", "bot-$botId@tianyan.local")
        config.save()
    }

    private fun hasCommits(repository: Repository): Boolean = runCatching { repository.resolve("HEAD") }.getOrNull() != null

    private fun ensureRepo(dir: File, botId: String) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 Bot 记忆目录: $dir")
        if (!RepositoryCacheAccess.hasRepository(dir)) {
            Git.init().setDirectory(dir).call().use { git ->
                configureIdentity(git, "Bot $botId", botId)
                writeSeedFiles(dir)
                commitAll(git, "Bot $botId", "chore: 初始化 Bot 记忆库")
            }
        }
    }

    private fun writeSeedFiles(dir: File) {
        File(dir, "knowledge").mkdirs()
        listOf(PERSONA, PREFERENCES, DIALOGUE_NOTES).forEach { name ->
            val file = File(dir, name)
            if (!file.exists()) file.writeText("", Charsets.UTF_8)
        }
        val keep = File(dir, "knowledge/.gitkeep")
        if (!keep.exists()) keep.writeText("", Charsets.UTF_8)
    }

    private fun commitAll(git: Git, authorName: String, message: String): RevCommit? {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        if (git.status().call().isClean) return null
        val email = git.repository.config.getString("user", null, "email") ?: "bot@tianyan.local"
        return git.commit()
            .setMessage(message)
            .setAuthor(authorName, email)
            .setCommitter(authorName, email)
            .call()
    }

    private fun toMemoryCommit(
        repository: Repository,
        commit: RevCommit,
        knownFiles: List<String>? = null,
    ): MemoryCommit = MemoryCommit(
        hash = commit.name,
        shortHash = commit.abbreviate(8).name(),
        message = commit.shortMessage,
        author = commit.authorIdent.name,
        timestamp = commit.commitTime.toLong() * 1000L,
        filesChanged = knownFiles ?: changedFiles(repository, commit),
    )

    private fun changedFiles(repository: Repository, commit: RevCommit): List<String> {
        if (commit.parentCount == 0) {
            return TreeWalk(repository).use { walk ->
                walk.addTree(commit.tree)
                walk.isRecursive = true
                buildList {
                    while (walk.next()) if (!walk.isSubtree) add(walk.pathString)
                }
            }
        }
        return DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(repository)
            RevWalk(repository).use { walk ->
                val parent = walk.parseCommit(commit.getParent(0).id)
                formatter.scan(parent.tree, commit.tree).map { if (it.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath }
            }
        }
    }

    private fun cleanStaleLock(dir: File) {
        val lock = File(dir, ".git/index.lock")
        if (!lock.isFile) return
        if (System.currentTimeMillis() - lock.lastModified() > STALE_LOCK_MS) {
            if (lock.delete()) logger.w("[BotMemoryManager] 已清理陈旧 Git 锁: ${lock.path}")
        }
    }

    private fun resolveSafe(root: File, relativePath: String): File {
        return MemoryFileAccess.resolve(root, relativePath)
    }

    private fun copyTreeSafely(source: File, dest: File, skipGitLocks: Boolean = false) {
        val sourceRoot = source.canonicalFile.toPath()
        val destRoot = dest.canonicalFile.toPath()
        source.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(sourceRoot) }.forEach { entry ->
            val relative = entry.relativeTo(source).invariantSeparatorsPath
            if (skipGitLocks && (relative.endsWith("index.lock") || relative.startsWith(".git/hooks"))) {
                return@forEach
            }
            val target = File(dest, relative)
            require(target.canonicalFile.toPath().startsWith(destRoot)) { "导入路径越界: $relative" }
            if (entry.isDirectory) {
                target.mkdirs()
            } else if (!Files.isSymbolicLink(entry.toPath())) {
                target.parentFile?.mkdirs()
                entry.copyTo(target, overwrite = true)
            }
        }
    }

    private fun StringBuilder.appendFileSection(title: String, file: File, limit: Int) {
        if (!file.isFile) return
        val text = readBounded(file, limit).trim()
        if (text.isBlank()) return
        appendLine("### $title")
        appendLine(text)
        appendLine()
    }

    private fun readBounded(file: File, limit: Int): String = file.reader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(limit)
        var length = 0
        while (length < limit) {
            val count = reader.read(buffer, length, limit - length)
            if (count < 0) break
            length += count
        }
        String(buffer, 0, length)
    }

    /** 避免在单测中触碰 JGit 的未迁移仓库。 */
    private object RepositoryCacheAccess {
        fun hasRepository(dir: File): Boolean = File(dir, ".git").isDirectory
    }

    companion object {
        const val PERSONA = "persona.md"
        const val PREFERENCES = "preferences.md"
        const val DIALOGUE_NOTES = "dialogue_notes.md"
        private const val STALE_LOCK_MS = 2 * 60 * 1000L
        private const val MAX_CONTEXT_CHARS = 6_000
        private const val MAX_KNOWLEDGE_FILES = 8
        private const val MAX_KNOWLEDGE_CHARS = 180
        private const val MAX_MEMORY_BYTES = 1024 * 1024
        private const val MAX_TOOL_CHARS = 24_000
    }
}
