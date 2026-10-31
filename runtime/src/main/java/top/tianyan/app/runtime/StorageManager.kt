package top.tianyan.app.runtime

import android.content.Context
import android.os.Process
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.runtime.proot.ProotMountLayout

/** 风险描述删除后果；READONLY 表示需要通过所属业务管理，而非文件批量删除。 */
enum class StorageRiskLevel { SAFE, CAUTION, DANGEROUS, READONLY }

data class StorageEntry(
    val id: String,
    val name: String,
    val detail: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val path: String? = null,
    val subItems: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageCategory(
    val id: String,
    val name: String,
    val description: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val entries: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageUsage(
    val availableBytes: Long = 0L,
    val safeCleanableBytes: Long = 0L,
    val cautionCleanableBytes: Long = 0L,
    val categories: List<StorageCategory> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
    val projectCleanableBytes: Long = 0L,
    val scanWarnings: List<String> = emptyList(),
    val excludedMountPointCount: Int = 0,
) {
    /** 文件逻辑大小，不等同于 Android 设置中的应用磁盘分配量。 */
    val totalManagedBytes: Long get() = categories.sumOf { it.bytes }
}

/**
 * 每个文件按最长路径规则只归属一次。扫描结果同时产生清理计划；执行不解析 UI ID 为路径，
 * 不递归删除目录，也不删除扫描后新增、修改或替换的文件。未知数据始终保留在所属类别。
 */
class StorageManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val runtime: Lazy<LinuxRuntime>,
) {
    private enum class Cleanup { NONE, OLD_LOG, DEPENDENCY, PROJECT_CACHE }
    private data class Rule(
        val root: Path,
        val category: String,
        val name: String,
        val hint: String,
        val cleanup: Cleanup = Cleanup.NONE,
        val risk: StorageRiskLevel = StorageRiskLevel.READONLY,
    ) {
        val id: String get() = "$category:$root"
    }
    private data class Target(val path: Path, val size: Long, val modified: FileTime, val key: Any?)
    private data class Plan(val rule: Rule, val targets: List<Target>)
    private val mutex = Mutex()
    private var plans: Map<String, Plan> = emptyMap()
    private var inspected = false
    private val categoryInfo = linkedMapOf(
        "projects" to ("项目与个人文件" to "工程、个人目录与项目缓存；源码和导出文件由工作区管理"),
        "environment" to ("Linux 与开发环境" to "按发行版查看系统、SDK 和共享运行时；通过环境或工具管理卸载"),
        "plugins" to ("插件与模型数据" to "插件程序、私有数据和模型；重置或卸载请使用所属工具"),
        "conversations" to ("会话与附件" to "会话数据库、附件和生成文件；删除需检查会话引用"),
        "cache" to ("缓存与安装资源" to "依赖缓存可按需清理；安装源包、暂存和回滚备份单独保留"),
        "app_data" to ("应用与诊断" to "配置、Skills、日志和其他数据；推荐清理过期归档日志"),
    )

    suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) { mutex.withLock { scan() } }

    private suspend fun scan(): StorageUsage {
        inspected = false
        plans = emptyMap()
        val now = System.currentTimeMillis()
        val coroutine = currentCoroutineContext()
        val rules = buildRules().sortedByDescending { it.root.nameCount }
        val totals = mutableMapOf<Rule, Long>()
        val targets = mutableMapOf<Rule, MutableList<Target>>()
        val warnings = mutableListOf<String>()
        val seenKeys = mutableSetOf<Any>()
        val roots = managedRoots()
        val mountCandidates = children(pathManager.distrosDir).filter { it.isDirectory }
            .flatMap { ProotMountLayout.placeholderCandidates(pathManager.rootfsDir(it.name)) }.toSet()
        val excludedMountPoints = mutableSetOf<Path>()
        fun excludeMountPoint(path: Path): Boolean {
            if (path !in mountCandidates || !safePath(path)) return false
            val stat = runCatching { Os.lstat(path.toString()) }.getOrNull() ?: return false
            val excluded = ProotMountLayout.isRestrictedPlaceholder(
                path, mountCandidates, OsConstants.S_ISDIR(stat.st_mode), stat.st_mode and 0xFFF,
                stat.st_uid, Process.myUid(),
            )
            if (excluded) excludedMountPoints.add(path)
            return excluded
        }
        for (root in roots) {
            if (!safePath(root) || !Files.exists(root, NOFOLLOW_LINKS)) continue
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (excludeMountPoint(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                    // 同一 inode 的硬链接只计一次，也不作为可释放文件（仍可能有其他链接）。
                    val key = attrs.fileKey()
                    if (key != null && !seenKeys.add(key)) return FileVisitResult.CONTINUE
                    val rule = rules.firstOrNull { file.startsWith(it.root) } ?: return FileVisitResult.CONTINUE
                    totals[rule] = (totals[rule] ?: 0L) + attrs.size()
                    if (eligible(rule, file, attrs, now) && singleLink(file)) {
                        targets.getOrPut(rule) { mutableListOf() }.add(Target(file, attrs.size(), attrs.lastModifiedTime(), key))
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    if (excludeMountPoint(file)) return FileVisitResult.CONTINUE
                    if (warnings.size < 20) warnings.add(scanFailureDetails(file, exc))
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null && excludeMountPoint(dir)) return FileVisitResult.CONTINUE
                    if (exc != null && warnings.size < 20) warnings.add(scanFailureDetails(dir, exc))
                    return FileVisitResult.CONTINUE
                }
            })
        }
        val entries = totals.map { (rule, bytes) ->
            val reclaimable = targets[rule].orEmpty().sumOf { it.size }
            rule to StorageEntry(
                id = rule.id, name = rule.name, detail = rule.hint, bytes = bytes,
                cleanable = reclaimable > 0, riskLevel = rule.risk, cleanupHint = rule.hint,
                path = rule.root.toString(), reclaimableBytes = reclaimable,
            )
        }
        val categories = categoryInfo.map { (id, info) ->
            val children = entries.filter { it.first.category == id }.map { it.second }.sortedByDescending { it.bytes }
            val cleanable = children.filter { it.cleanable }
            StorageCategory(
                id = id, name = info.first, description = info.second, bytes = children.sumOf { it.bytes },
                cleanable = cleanable.isNotEmpty(),
                riskLevel = cleanable.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: StorageRiskLevel.READONLY,
                cleanupHint = "仅处理下列可清理项中扫描时已存在且未改变的文件。依赖需重新下载，项目缓存需重新生成；使用中的环境请先停止。",
                entries = children, reclaimableBytes = children.sumOf { it.reclaimableBytes },
            )
        }
        plans = targets.mapKeys { it.key.id }.mapValues { (id, files) -> Plan(rules.first { it.id == id }, files.toList()) }
        inspected = true
        return StorageUsage(
            availableBytes = availableBytes(),
            categories = categories, scannedAt = now, scanWarnings = warnings,
            excludedMountPointCount = excludedMountPoints.size,
            safeCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.SAFE }.sumOf { it.second.reclaimableBytes },
            cautionCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.CAUTION }.sumOf { it.second.reclaimableBytes },
            projectCleanableBytes = entries.filter { it.first.cleanup == Cleanup.PROJECT_CACHE }.sumOf { it.second.reclaimableBytes },
        )
    }

    private fun buildRules(): List<Rule> = buildList {
        fun addRule(file: File, category: String, name: String, hint: String, cleanup: Cleanup = Cleanup.NONE,
                    risk: StorageRiskLevel = StorageRiskLevel.READONLY) {
            add(Rule(storagePath(file), category, name, hint, cleanup, risk))
        }
        val preserve = "由所属功能管理；不直接批量删除文件"
        addRule(context.filesDir.parentFile!!, "app_data", "应用私有数据", "包含偏好、WebView、备份配置及其他数据，由所属功能管理")
        addRule(context.filesDir, "app_data", "应用配置与其他文件", preserve)
        addRule(pathManager.baseDir, "app_data", "运行时元数据与其他文件", preserve)
        addRule(context.getDatabasePath("tianyan.db").parentFile!!, "conversations", "会话与应用数据库", "通过会话管理删除记录，保留数据库及 WAL/SHM 文件")
        runCatching { context.cacheDir }.getOrNull()?.let {
            addRule(it, "cache", "应用导入与系统缓存", "可能包含正在导入的文件，由所属功能管理")
        }
        runCatching { context.codeCacheDir }.getOrNull()?.let {
            addRule(it, "app_data", "应用代码缓存", "由 Android 管理")
        }
        addRule(pathManager.cacheDir, "cache", "下载与离线安装归档", "保留离线安装与下载中的文件；应由安装管理确认完成后删除")
        addRule(File(context.filesDir, "plugins"), "cache", "本地插件源包", "重新安装和插件配方可能依赖源包，请在插件管理中处理")
        addRule(pathManager.stagingRootfsDir, "cache", "全局安装暂存", "安装事务所有的数据，不参与一键清理")
        addRule(pathManager.tmpDir, "app_data", "运行临时文件", "可能被 PRoot 和进程使用，不参与一键清理")
        val logHint = "仅清理超过 7 天且未修改的 .log.gz / .log.数字 归档日志；保留当前日志"
        addRule(pathManager.logsDir, "app_data", "运行日志", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
        addRule(pathManager.attachmentsDir, "conversations", "会话附件与生成文件", "可能是唯一副本；请在会话或文件管理中核对后删除", risk = StorageRiskLevel.DANGEROUS)
        addRule(File(pathManager.attachmentsDir, "skills"), "app_data", "自定义 Skills", preserve)
        addRule(pathManager.workspaceDir, "projects", "工作区其他文件", "包含导出文件和未识别项目；请在工作区管理")
        children(pathManager.workspaceDir).filter { it.isDirectory }.forEach { project ->
            addRule(project, "projects", "项目 ${project.name} · 文件", "源码、依赖和产物；不凭 build/dist/out/target 名称自动删除")
            // 只识别有工程标志的专用缓存，不推断自定义构建输出路径。
            val gradle = File(project, "settings.gradle").isFile || File(project, "settings.gradle.kts").isFile ||
                File(project, "build.gradle").isFile || File(project, "build.gradle.kts").isFile
            if (gradle) addRule(File(project, ".gradle"), "projects", "项目 ${project.name} · Gradle 缓存",
                "清理项目 .gradle 缓存；下次构建会重新生成。保留 build、dist、out、target 及源码", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
            if (File(project, "pubspec.yaml").isFile) addRule(File(project, ".dart_tool"), "projects", "项目 ${project.name} · Dart 缓存",
                "清理 .dart_tool；需要重新执行 pub get 和构建", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
        }
        children(pathManager.distrosDir).filter { it.isDirectory }.forEach { distro ->
            val id = distro.name
            val rfs = pathManager.rootfsDir(id)
            val home = pathManager.homeDir(id)
            val tianyan = pathManager.tianyanRootDir(id)
            addRule(distro, "environment", "$id · 系统与其他环境文件", "包含 Linux 系统和自行安装的软件，大小会随使用变化")
            addRule(home, "projects", "$id · 个人目录 /root", "独立持久化 home 挂载；包含个人文件、配置和未识别依赖")
            addRule(File(rfs, "root"), "environment", "$id · 镜像内原始 /root", "该目录被独立 home 挂载覆盖，保留镜像原始数据")
            addRule(File(rfs, "home"), "projects", "$id · 其他用户目录", preserve)
            addRule(pathManager.rootfsPreviousDir(id), "cache", "$id · 升级回滚备份", "删除会失去回滚能力；由升级事务确认并处理", risk = StorageRiskLevel.DANGEROUS)
            addRule(pathManager.stagingRootfsDir(id), "cache", "$id · 安装暂存", "可能正在安装或等待恢复，不参与一键清理")
            addRule(File(tianyan, "imports"), "cache", "$id · 插件安装资源", "可能被配方或安装事务使用，不参与一键清理")
            addRule(File(tianyan, "flutter-cache-arm64"), "cache", "$id · Flutter 安装资源", "由安装管理确认完成后处理")
            addRule(File(rfs, "tmp"), "app_data", "$id · 临时文件", "进程运行数据，不参与一键清理")
            addRule(File(rfs, "var/tmp"), "app_data", "$id · 持久临时文件", "可能需要跨重启保留，不参与一键清理")
            addRule(File(rfs, "var/log"), "app_data", "$id · 系统日志", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
            listOf(pathManager.tianyanToolsDir(id), pathManager.tianyanDataDir(id)).forEach { dir ->
                addRule(dir, "plugins", "$id · ${dir.name}", preserve)
                children(dir).forEach { plugin ->
                    addRule(plugin, "plugins", "$id · ${plugin.name} · ${if (dir.name == "data") "数据与模型" else "程序"}",
                        "请通过插件功能卸载或重置，以维护引用和注册表一致性")
                }
            }
            listOf(File(tianyan, "toolchains"), pathManager.tianyanRuntimesDir(id), File(tianyan, "compat"), File(rfs, "opt")).forEach { dir ->
                children(dir).filter { it.name != "tianyan" }.forEach { sdk ->
                    addRule(sdk, "environment", "$id · ${sdk.name}", "开发套件或共享运行时；卸载前需检查插件和项目引用")
                }
            }
            listOf(".rustup", ".nvm", ".sdkman", ".pyenv").forEach {
                addRule(File(home, it), "environment", "$id · $it", "用户安装的语言环境，由对应版本管理器管理")
            }
            addRule(File(home, ".gradle/wrapper/dists"), "environment", "$id · Gradle Wrapper 分发版",
                "含已解压运行组件和安装标记；按完整版本管理，不能仅删除其中的旧文件")
            addRule(File(home, ".gradle/caches"), "cache", "$id · Gradle 转换与其他缓存",
                "部分缓存含完整性元数据，由 Gradle 管理；下载依赖单独列出")
            addRule(File(home, ".cache/yarn"), "cache", "$id · Yarn 缓存",
                "可能包含已解压包及完整性标记，请通过 Yarn 管理")
            val dependencyHint = "仅删除超过 7 天且未修改的缓存文件；需要联网重新下载，离线构建可能受影响。请先停止终端、构建和服务"
            // 仅归档/下载缓存；不清理 pub 全局激活、npm npx 环境、Go 解压源码或 pnpm store。
            listOf(
                ".gradle/caches/modules-2/files-2.1" to "Gradle 下载依赖",
                ".cache/pip" to "Pip 下载缓存",
                ".npm/_cacache" to "NPM 下载缓存",
                ".cargo/registry/cache" to "Cargo 下载归档",
                "go/pkg/mod/cache/download" to "Go 下载缓存",
            ).forEach { (relative, name) ->
                addRule(File(home, relative), "cache", "$id · $name", dependencyHint, Cleanup.DEPENDENCY, StorageRiskLevel.CAUTION)
            }
            addRule(File(rfs, "var/cache/apt/archives"), "cache", "$id · APT 已下载软件包",
                dependencyHint, Cleanup.DEPENDENCY, StorageRiskLevel.CAUTION)
        }
    }

    private fun children(dir: File): List<File> = if (safePath(storagePath(dir))) dir.listFiles().orEmpty().filter {
        !Files.isSymbolicLink(it.toPath())
    } else emptyList()

    /** lstat inspects the entry itself, including dangling links, without following its target. */
    private fun scanFailureDetails(path: Path, error: IOException?): String = buildString {
        append("未完整读取：").append(path)
        append("\n错误：").append(error?.javaClass?.simpleName ?: "未知读取错误")
        (error as? FileSystemException)?.reason?.takeIf { it.isNotBlank() }?.let {
            append(" · ").append(it)
        }
        // Mirrors the useful metadata inspection in MTDataFilesProvider, not its SAF export.
        // No chmod: a scan must not alter guest permissions or expose app-private files.
        runCatching {
            val stat = Os.lstat(path.toString())
            append("\n权限：").append(Integer.toOctalString(stat.st_mode and 0xFFF))
            append(" · UID：").append(stat.st_uid).append(" · GID：").append(stat.st_gid)
            append(" · 应用 UID：").append(Process.myUid())
            if (OsConstants.S_ISLNK(stat.st_mode)) {
                append("\n链接目标：").append(Os.readlink(path.toString()))
            } else if (OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) {
                val needed = OsConstants.S_IRUSR or OsConstants.S_IXUSR
                if (stat.st_mode and needed != needed) {
                    append("\n该目录的所有者权限缺少读取或进入权限；与共享存储授权不同。")
                }
            }
        }.onFailure {
            append("\n元数据读取失败：").append(it.javaClass.simpleName)
            it.message?.let { message -> append(" · ").append(message) }
        }
    }

    /** Resolve only the trusted Android app root alias, never an arbitrary child symlink. */
    private fun storagePath(file: File): Path {
        val appRoot = context.filesDir.parentFile!!
        val logicalRoot = appRoot.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        return if (path.startsWith(logicalRoot)) appRoot.canonicalFile.toPath().resolve(logicalRoot.relativize(path)) else path
    }

    private fun managedRoots(): List<Path> {
        val paths = listOfNotNull(context.filesDir.parentFile, context.getDatabasePath("tianyan.db").parentFile,
            runCatching { context.cacheDir }.getOrNull(), runCatching { context.codeCacheDir }.getOrNull())
            .map(::storagePath).distinct().sortedBy { it.nameCount }
        return paths.filter { path -> paths.none { it != path && path.startsWith(it) } }
    }

    /** 检查全部祖先，不能仅检查叶子链接；PRoot 内的绝对/相对链接均不遍历。 */
    private fun safePath(path: Path): Boolean {
        var cursor: Path? = path.toAbsolutePath().normalize()
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) return false
            cursor = cursor.parent
        }
        return true
    }

    private fun singleLink(path: Path): Boolean = try {
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1
    } catch (_: UnsupportedOperationException) {
        // Windows 单元测试文件系统不提供 unix 属性；Android/Linux 支持 nlink。
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IllegalArgumentException) {
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IOException) { false }

    private fun eligible(rule: Rule, path: Path, attrs: BasicFileAttributes, now: Long): Boolean {
        if (rule.cleanup == Cleanup.NONE || now - attrs.lastModifiedTime().toMillis() < RETENTION_MS) return false
        val relative = rule.root.relativize(path)
        if (relative.any { it.toString() in setOf("partial", ".git") }) return false
        val name = path.fileName.toString()
        if (name == "lock" || name.endsWith(".lock") || name.endsWith(".part") || name.endsWith(".lck")) return false
        return rule.cleanup != Cleanup.OLD_LOG || name.endsWith(".log.gz") || Regex(".*\\.log\\.[0-9]+(?:\\.gz)?").matches(name)
    }

    suspend fun quickSafeClean(): AppResult<Long> = clean { it.rule.risk == StorageRiskLevel.SAFE }

    /** 历史 API 名保留：只清理已识别项目缓存，绝不猜测 build/dist/out/target 的用途。 */
    suspend fun cleanProjectBuildArtifacts(projectName: String? = null): AppResult<Long> = clean(requireMatch = projectName != null) { plan ->
        plan.rule.cleanup == Cleanup.PROJECT_CACHE && (projectName == null ||
            plan.rule.root.parent == storagePath(pathManager.workspaceDir).resolve(projectName))
    }

    suspend fun clearCategory(categoryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId }.map { }

    suspend fun clearEntry(categoryId: String, entryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId && it.rule.id == entryId }.map { }

    suspend fun clearCache(): AppResult<Unit> = clearCategory("cache")

    private suspend fun clean(requireMatch: Boolean = false, select: (Plan) -> Boolean): AppResult<Long> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    check(inspected) { "请先刷新存储占用并查看清理范围" }
                    val selected = plans.values.filter(select)
                    check(!requireMatch || selected.isNotEmpty()) { "此项不可直接清理或已失效，请刷新后重试" }
                    var released = 0L
                    var skipped = 0
                    var failed = 0
                    runtime.value.withStorageCleanup {
                        for (plan in selected) for (target in plan.targets) {
                            currentCoroutineContext().ensureActive()
                            try {
                                check(target.path.startsWith(plan.rule.root) && safePath(target.path)) { "路径已改变" }
                                if (!Files.exists(target.path, NOFOLLOW_LINKS)) { skipped++; continue }
                                val attrs = Files.readAttributes(target.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != target.size ||
                                    attrs.lastModifiedTime() != target.modified || attrs.fileKey() != target.key ||
                                    !singleLink(target.path) || !eligible(plan.rule, target.path, attrs, System.currentTimeMillis())) {
                                    skipped++
                                    continue
                                }
                                if (Files.deleteIfExists(target.path)) released += target.size
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (_: Exception) { failed++ }
                        }
                    }
                    plans = emptyMap()
                    inspected = false
                    // 部分完成不能伪装成成功，保留明确结果供 UI 呈现。
                    if (failed > 0 || skipped > 0) AppResult.Failure(AppError(ErrorCode.IO,
                        "已删除 $released 字节；跳过 $skipped 个已改变或不存在的文件，失败 $failed 个。请刷新查看剩余占用"))
                    else AppResult.Success(released)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    AppResult.Failure(AppError(ErrorCode.IO, error.message ?: "清理失败", error))
                }
            }
        }

    fun hasEnoughSpace(requiredBytes: Long): Boolean = availableBytes() >= requiredBytes
    private fun availableBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) { context.filesDir.usableSpace }

    private companion object { const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 }
}
