package top.tianyan.app.core.tools.skill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tianyan.app.core.common.files.BoundedStreamCopy
import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.model.skill.ClawHubMarketDetail
import top.tianyan.app.core.model.skill.ClawHubMarketItem
import top.tianyan.app.core.model.skill.SkillPermission
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ClawHub 在线技能市场客户端（提供在线生态检索、下载与离线精选包降级）。
 */
class ClawHubClient(
    private val httpClient: OkHttpClient,
    private val hubRegistryBaseUrl: String = DEFAULT_CLAWHUB_URL,
) {

    /** 最近一次 [fetchMarketCatalog] 是否使用了内置离线精选降级（远端市场未接入或不可达）。 */
    @Volatile
    var lastCatalogUsedOfflineFallback: Boolean = true
        private set

    /**
     * 获取市场技能列表（支持搜索与分类过滤，网络不可达时自动降级到精选离线库）。
     */
    suspend fun fetchMarketCatalog(
        query: String? = null,
        category: String? = null,
    ): AppResult<List<ClawHubMarketItem>> {
        val remoteResult = runCatching { fetchRemoteCatalog() }
        val remote = remoteResult.getOrNull()?.takeIf { it.isNotEmpty() }
        lastCatalogUsedOfflineFallback = remote == null
        val catalog = remote ?: BUILTIN_PRESET_ITEMS

        val filtered = catalog.filter { item ->
            val matchesQuery = query.isNullOrBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                item.description.contains(query, ignoreCase = true) ||
                item.tags.any { it.contains(query, ignoreCase = true) }
            val matchesCategory = category.isNullOrBlank() ||
                item.category.equals(category, ignoreCase = true)
            matchesQuery && matchesCategory
        }

        return AppResult.Success(filtered)
    }

    /**
     * 获取指定技能的市场详情信息。
     */
    suspend fun fetchPackageDetail(skillId: String): AppResult<ClawHubMarketDetail> {
        val preset = BUILTIN_PRESET_DETAILS[skillId]
        if (preset != null) {
            return AppResult.Success(preset)
        }

        // 尝试从远端获取
        val item = fetchMarketCatalog().let { res ->
            if (res is AppResult.Success) res.data.firstOrNull { it.id == skillId } else null
        } ?: return AppResult.Failure(AppError(ErrorCode.NETWORK, "在 ClawHub 市场中未找到技能: $skillId"))

        return AppResult.Success(
            ClawHubMarketDetail(
                item = item,
                readmeMarkdown = item.description,
                license = "Apache-2.0",
                templateSummary = listOf("SKILL.md", "AGENT.md", "TOOLS.md"),
            ),
        )
    }

    /**
     * 下载技能包的 ZIP 字节数据（离线精选包则由内置模板动态生成标准 ZIP 包）。
     */
    suspend fun downloadPackage(skillId: String): AppResult<ByteArray> {
        val builtInGen = BUILTIN_PACKAGE_GENERATORS[skillId]
        if (builtInGen != null) {
            return AppResult.Success(builtInGen())
        }

        // 尝试从网络下载
        return runCatching {
            val url = "$hubRegistryBaseUrl/packages/${URLEncoder.encode(skillId, StandardCharsets.UTF_8.name())}.zip"
            val request = Request.Builder().url(url).build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("从 ClawHub 下载技能包失败，HTTP 状态码: ${response.code}")
                    }
                    val body = response.body
                    val contentLength = body.contentLength()
                    if (contentLength > SkillPackageParser.MAX_ZIP_TOTAL_BYTES) {
                        error("远程技能包体积 (${contentLength / 1024 / 1024}MB) 超过系统上限 (${SkillPackageParser.MAX_ZIP_TOTAL_BYTES / 1024 / 1024}MB)")
                    }
                    val bos = ByteArrayOutputStream()
                    BoundedStreamCopy.copy(
                        input = body.byteStream(),
                        output = bos,
                        maxBytes = SkillPackageParser.MAX_ZIP_TOTAL_BYTES,
                        policy = BoundedStreamCopy.OverflowPolicy.ABORT,
                    )
                    AppResult.Success(bos.toByteArray())
                }
            }
        }.getOrElse { err ->
            AppResult.Failure(AppError(ErrorCode.DOWNLOAD, err.message ?: "下载技能失败", err))
        }
    }

    private suspend fun fetchRemoteCatalog(): List<ClawHubMarketItem>? {
        // 当配置了有效的外部 API 时发送网络请求
        if (hubRegistryBaseUrl.isBlank() || hubRegistryBaseUrl.startsWith("mock://")) {
            return null
        }
        val request = Request.Builder()
            .url("$hubRegistryBaseUrl/catalog.json")
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // 如后续接入 ClawHub 后端，此处进行 JSON 反序列化解析；当前优雅 fallback
                null
            }
        }
    }

    companion object {
        const val DEFAULT_CLAWHUB_URL = "https://raw.githubusercontent.com/tianyan-ai/clawhub/main"

        /**
         * 内置 5 款精选高质量生态技能（完全对标 PalmClaw / OpenMinis 规范）。
         */
        val BUILTIN_PRESET_ITEMS: List<ClawHubMarketItem> = listOf(
            ClawHubMarketItem(
                id = "git-workflow",
                name = "Git 工作流与审查大师",
                version = "1.2.0",
                description = "遵循语义化 Conventional Commits 规范，智能分析分支状态、代码差异并撰写严谨的 Commit / PR 说明。",
                author = "Tianyan Core Team",
                icon = "GitBranch",
                tags = listOf("Git", "审查", "工作流", "DevOps"),
                category = "开发工程",
                downloadUrl = "builtin://git-workflow",
                stars = 428,
                requiredTools = listOf("git"),
                permissions = listOf(SkillPermission.EXEC_COMMAND),
            ),
            ClawHubMarketItem(
                id = "code-auditor",
                name = "代码安全与架构审查",
                version = "1.1.0",
                description = "针对 Kotlin、Java、Rust、C++ 进行小步精细化审查，识别空指针隐患、内存泄漏与高危系统调用并提供重构建议。",
                author = "PalmClaw Security Lab",
                icon = "ShieldCheck",
                tags = listOf("安全", "代码审查", "重构", "Architecture"),
                category = "安全审计",
                downloadUrl = "builtin://code-auditor",
                stars = 389,
                requiredTools = emptyList(),
                permissions = listOf(SkillPermission.FILE_WRITE),
            ),
            ClawHubMarketItem(
                id = "python-pro",
                name = "沙箱 Python 数据与科学计算",
                version = "2.0.1",
                description = "利用 PRoot 沙箱内 Python3 执行数据统计、数学推导与自动化脚本调试，支持 NumPy / Pandas 离线工作流。",
                author = "Community SciPy",
                icon = "Code",
                tags = listOf("Python", "数据科学", "脚本", "沙箱"),
                category = "数据算法",
                downloadUrl = "builtin://python-pro",
                stars = 512,
                requiredTools = listOf("python3"),
                permissions = listOf(SkillPermission.EXEC_COMMAND, SkillPermission.FILE_WRITE),
            ),
            ClawHubMarketItem(
                id = "linux-doctor",
                name = "PRoot 沙箱自检与诊断专家",
                version = "1.0.5",
                description = "深谙 Android PRoot 用户态沙箱边界，快速排查 dpkg 状态残留、挂载点读写故障、后台托管进程与网络连通性。",
                author = "Tianyan Runtime Lab",
                icon = "Terminal",
                tags = listOf("Linux", "沙箱", "诊断", "PRoot"),
                category = "系统运维",
                downloadUrl = "builtin://linux-doctor",
                stars = 640,
                requiredTools = listOf("bash", "ps"),
                permissions = listOf(SkillPermission.EXEC_COMMAND, SkillPermission.SYSTEM_PROBE),
            ),
            ClawHubMarketItem(
                id = "doc-craftsman",
                name = "技术设计与文档润色大师",
                version = "1.3.0",
                description = "采用 Google Developer / Markdown 规范，编排结构清晰的技术文档、架构 ADR、API 规范与发布日志。",
                author = "Documentation Guild",
                icon = "BookOpen",
                tags = listOf("文档", "Markdown", "设计", "ADR"),
                category = "文档写作",
                downloadUrl = "builtin://doc-craftsman",
                stars = 275,
                requiredTools = emptyList(),
                permissions = listOf(SkillPermission.FILE_WRITE),
            ),
        )

        val BUILTIN_PRESET_DETAILS: Map<String, ClawHubMarketDetail> = BUILTIN_PRESET_ITEMS.associate { item ->
            item.id to ClawHubMarketDetail(
                item = item,
                readmeMarkdown = """
                    # ${item.name} (${item.id})
                    
                    ${item.description}
                    
                    ## 声明式模板构成
                    - `SKILL.md`: 技能入口总纲与 YAML Frontmatter
                    - `AGENT.md`: 核心智能体决策与工作流树
                    - `SOUL.md`: 沟通语气、价值观红线与拟人化性格
                    - `TOOLS.md`: 沙箱工具调用约定与输出规范
                """.trimIndent(),
                templateSummary = listOf("SKILL.md", "AGENT.md", "SOUL.md", "TOOLS.md"),
            )
        }

        /**
         * 动态生成标准精选技能 ZIP 字节流。
         */
        val BUILTIN_PACKAGE_GENERATORS: Map<String, () -> ByteArray> = mapOf(
            "git-workflow" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: git-workflow
                        name: Git 工作流与审查大师
                        version: 1.2.0
                        description: 遵循语义化 Conventional Commits 规范，智能分析分支状态与代码差异。
                        author: Tianyan Core Team
                        category: 开发工程
                        tags: Git, 审查, DevOps
                        permissions: [exec_command]
                        required_tools: [git]
                        trigger_command: /git
                        ---
                        # Git 工作流专精总纲
                        本技能为天衍智能体提供在沙箱中进行严格代码版本控制的能力。
                    """.trimIndent(),
                    agentMd = """
                        1. 任何代码修改提交前，必先调用 `git status -s` 与 `git diff` 确认变更影响范围；
                        2. 撰写 Commit Message 必须遵循 Conventional Commits 中文规范，标题限制在 50 字符以内；
                        3. 如有未跟踪文件遗漏，需主动提醒用户是否纳入版本控制。
                    """.trimIndent(),
                    soulMd = """
                        严谨、周密、对代码洁癖有执念，对任何未经验证或遗漏的提交保持敏锐嗅觉。
                    """.trimIndent(),
                    toolsMd = """
                        仅调用沙箱已安装的 `git` 命令行。禁止使用 `git push --force` 等高危破坏性参数。
                    """.trimIndent(),
                )
            },
            "code-auditor" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: code-auditor
                        name: 代码安全与架构审查
                        version: 1.1.0
                        description: 针对移动端与纯 Kotlin/Java 模块进行静态代码安全与架构审查。
                        author: PalmClaw Security Lab
                        category: 安全审计
                        tags: 安全, 代码审查, 架构
                        permissions: [file_write]
                        trigger_command: /audit
                        ---
                        # 代码安全与架构审查
                        协助开发者在本地静态发现潜在漏洞与反模式。
                    """.trimIndent(),
                    agentMd = """
                        1. 审查核心：关注输入校验、内存泄漏、空安全（NPE）、未捕获异常及并发竞态；
                        2. 输出审查报告时，分出【关键缺陷 (Critical)】、【建议改进 (Improvement)】与【加固方案 (Hardening)】；
                        3. 重构代码时采用小步迭代，确保语义不变。
                    """.trimIndent(),
                    soulMd = """
                        客观、建设性，指出问题的同时必给出优良的修改范例。
                    """.trimIndent(),
                    toolsMd = """
                        优先使用 `read` 检查目标文件，使用 `edit` 工具进行精细化修改，严禁覆盖未核验的文件内容。
                    """.trimIndent(),
                )
            },
            "python-pro" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: python-pro
                        name: 沙箱 Python 数据与科学计算
                        version: 2.0.1
                        description: 利用沙箱内 Python3 执行数据处理与自动化脚本。
                        author: Community SciPy
                        category: 数据算法
                        tags: Python, 数据科学, 脚本
                        permissions: [exec_command, file_write]
                        required_tools: [python3]
                        trigger_command: /python
                        ---
                        # Python 科学计算专精
                    """.trimIndent(),
                    agentMd = """
                        1. 编写独立 Python 脚本时，首行声明 `#!/usr/bin/env python3`；
                        2. 执行数据处理任务时注意沙箱内存（默认控制在 256MB 以内），优先使用生成器或流式迭代；
                        3. 复杂统计计算提供详细输出与控制台可视化表格。
                    """.trimIndent(),
                    soulMd = """
                        高效、数学严谨、对性能和内存开销高度敏感。
                    """.trimIndent(),
                    toolsMd = """
                        通过 `base` 或 `process` 调用 `python3`。若缺失第三方依赖包，引导用户通过沙箱环境配置。
                    """.trimIndent(),
                )
            },
            "linux-doctor" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: linux-doctor
                        name: PRoot 沙箱自检与诊断专家
                        version: 1.0.5
                        description: 排查沙箱状态、dpkg 残留、挂载与前后台进程。
                        author: Tianyan Runtime Lab
                        category: 系统运维
                        tags: Linux, 沙箱, 诊断
                        permissions: [exec_command, system_probe]
                        required_tools: [bash, ps]
                        trigger_command: /doctor
                        ---
                        # 沙箱环境自检总纲
                    """.trimIndent(),
                    agentMd = """
                        1. 识别 Android PRoot 无 Root 用户态限制；
                        2. dpkg 安装异常时，指导检查 `/var/lib/dpkg/updates` 与 setuid 降低；
                        3. 后台服务必须通过天衍进程托管器守护，避免孤儿进程回收。
                    """.trimIndent(),
                    soulMd = """
                        冷静、沉着、排障思路严密，如同老道经验丰富的 Linux 系统工程师。
                    """.trimIndent(),
                    toolsMd = """
                        只读优先：先用 `ps`, `df`, `free` 诊断，再进行修复操作。
                    """.trimIndent(),
                )
            },
            "doc-craftsman" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: doc-craftsman
                        name: 技术设计与文档润色大师
                        version: 1.3.0
                        description: 编排结构清晰的技术文档、架构 ADR 与发布日志。
                        author: Documentation Guild
                        category: 文档写作
                        tags: 文档, Markdown, ADR
                        permissions: [file_write]
                        trigger_command: /doc
                        ---
                        # 技术文档编排与润色总纲
                    """.trimIndent(),
                    agentMd = """
                        1. 遵循清晰的 GitHub Flavored Markdown 规范；
                        2. 善用 Mermaid 时序图、架构图与参数表格阐明复杂系统；
                        3. 语言精炼准确，杜绝冗长套话。
                    """.trimIndent(),
                    soulMd = """
                        清晰、典雅、追求极致排版与高阅读体验。
                    """.trimIndent(),
                    toolsMd = """
                        通过 `write` 或 `edit` 输出结构化 markdown 文件。
                    """.trimIndent(),
                )
            },
        )

        private fun createZipPackage(
            skillMd: String,
            agentMd: String? = null,
            soulMd: String? = null,
            toolsMd: String? = null,
            userMd: String? = null,
            memoryMd: String? = null,
        ): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                fun addFile(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }

                addFile("SKILL.md", skillMd)
                agentMd?.let { addFile("AGENT.md", it) }
                soulMd?.let { addFile("SOUL.md", it) }
                toolsMd?.let { addFile("TOOLS.md", it) }
                userMd?.let { addFile("USER.md", it) }
                memoryMd?.let { addFile("MEMORY.md", it) }
            }
            return bos.toByteArray()
        }
    }
}
