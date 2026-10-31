package top.tianyan.app.iteration.engine

import java.io.File

/**
 * CustomIterationBootstrap — 天衍自定义迭代环境自举引擎。
 *
 * 职责：
 * 1. 只 `mkdir` 空的隔离工作区 `~/custom_tianyan`，供随后 `git clone` 使用；
 * 2. 把 `tianyan-custom-iteration` Skill 落到 Agent 的真实扫描根
 *    （`<attachmentsDir>/skills` 与 `<workspaceDir>/skills`）；
 * 3. 把 GitHub Actions 工作流模板写到克隆目标之外的缓存；
 * 4. 生成引导 Prompt。云端构建目标使用已解析的 Fork，解析不到时要求 Agent 先读 `git remote`。
 *
 * 路径契约（与 [top.tianyan.app.runtime.RuntimePathManager] / PRoot 绑定一致）：
 * - 宿主 `homeDir` 绑定到 guest `/root`，工作区即 `homeDir/custom_tianyan` → `~/custom_tianyan`；
 * - `attachmentsDir` 绑定到 `/attachments`，`workspaceDir` 绑定到 `/workspace`。
 *
 * `AgentSkillRepository.standardScanRoots` 只扫描上述两个 `skills` 目录，以及
 * **workspaceDir 树下**的 `.agents/skills`。`~/custom_tianyan` 不在这棵树里，
 * 因此克隆前不会向 `~/custom_tianyan/.agents` 或 `~/custom_tianyan/.github` 写入任何文件。
 * 项目级 Skill / 工作流若要进入 Fork，由 Prompt 在克隆完成之后复制。
 */
object CustomIterationBootstrap {

    const val WORKSPACE_NAME = "custom_tianyan"
    const val OFFICIAL_REPO = "https://github.com/tianyan-lab/tianyan"
    const val OFFICIAL_REPO_SLUG = "tianyan-lab/tianyan"
    const val DEFAULT_BRANCH = "main"

    /** 模块 assets 中的 Skill 名称与工作流文件名。 */
    const val SKILL_NAME = "tianyan-custom-iteration"
    const val WORKFLOW_FILE = "tianyandev-build.yml"

    /** assets 内源路径（随 feature:custom_iteration 模块合并进 APK）。 */
    const val ASSET_SKILL = "skills/$SKILL_NAME/SKILL.md"
    const val ASSET_WORKFLOW = "templates/workflows/$WORKFLOW_FILE"

    /** 挂到 attachments / workspace 两个真实扫描根之下的 Skill 相对路径。 */
    const val SKILL_RELATIVE_IN_SCAN_ROOT = "skills/$SKILL_NAME/SKILL.md"

    /**
     * 工作流模板缓存，相对 [CustomIterationPaths.attachmentsDir]。
     * guest 视角为 [GUEST_WORKFLOW_CACHE]，不在克隆目标内。
     */
    const val WORKFLOW_CACHE_RELATIVE = "templates/workflows/$WORKFLOW_FILE"

    /** 克隆完成之后、可选地提交进 Fork 的相对路径。自举阶段不创建它们。 */
    const val WORKFLOW_RELATIVE = ".github/workflows/$WORKFLOW_FILE"
    const val AGENT_SKILL_RELATIVE = ".agents/skills/$SKILL_NAME/SKILL.md"

    /** guest 视角的绝对路径，用于生成提示词。 */
    const val GUEST_HOME = "/root"
    const val GUEST_ATTACHMENTS_SKILLS = "/attachments/skills"
    const val GUEST_WORKSPACE_SKILLS = "/workspace/skills"
    const val GUEST_WORKFLOW_CACHE = "/attachments/$WORKFLOW_CACHE_RELATIVE"

    /** 工作区在 guest 视角的绝对路径，即 PRoot 里 `~/$WORKSPACE_NAME`。 */
    const val GUEST_WORKSPACE = "$GUEST_HOME/$WORKSPACE_NAME"

    /**
     * 工作区尚未克隆、或 origin 无法解析为 `owner/name` 时写入 Prompt 的占位。
     * 它不是合法仓库标识，Agent 必须先执行 `git remote get-url origin`。
     */
    const val FORK_REPO_PLACEHOLDER =
        "<先执行 git remote get-url origin 解析 owner/name；禁止对 tianyan-lab/tianyan 执行 workflow_dispatch>"

    /** 工作流模板里的关键锚点，用于校验落盘内容确为可用模板。 */
    private const val WORKFLOW_REQUIRED_MARKER = "TAIXU_DEV_BUILD"

    /**
     * 生成引导 Agent 执行自迭代开发的提示词。
     *
     * [forkRepo] 为已解析的 `owner/name`。空白或无法通过仓库标识校验时改用
     * [FORK_REPO_PLACEHOLDER]，避免把 `workflow_dispatch` 指到官方仓库导致 403。
     */
    fun bootstrapPrompt(guestWorkspace: String, forkRepo: String? = null): String {
        val forkLabel = promptForkLabel(forkRepo)
        return """我准备在天衍（Tianyan）的手机 Linux 虚拟沙盒中进行 Tianyan 自定义迭代。

请按以下步骤引导我：
1. 检查本地开发环境：
   - 确认当前命令在 Linux PRoot 沙盒中执行（guest 视角工作区 = $guestWorkspace）；
   - 确认独立工作区为 ~/$WORKSPACE_NAME；
   - 优先使用 GitHub Actions 构建 TianyanDev APK，无需在手机本地安装庞大的 Android SDK/NDK。

2. 检查并配置 GitHub 认证：
   - 检查 gh CLI 与 Git 配置；
   - 引导我使用 gh auth login（设备码流程）或 SSH Key 完成登录验证；
   - 不要把 Token、私钥写入命令历史或日志中。

3. 验证与克隆仓库：
   - 为官方仓库 $OFFICIAL_REPO 点星；
   - Fork $DEFAULT_BRANCH 分支到我的 GitHub 账户；
   - 克隆前不要向 $guestWorkspace 写入任何文件（包括 .github 与 .agents）；
   - 若 $guestWorkspace 为空目录，将 Fork 克隆进去（git clone <fork-url> $guestWorkspace）；
   - 若该目录已经是 git 仓库，不要再次克隆，直接使用它。

4. 遵循 $SKILL_NAME Skill 开发规范：
   - Skill 已落在真实扫描根 $GUEST_ATTACHMENTS_SKILLS/$SKILL_NAME 与 $GUEST_WORKSPACE_SKILLS/$SKILL_NAME，按该规范修改；
   - $guestWorkspace/.agents/skills 不是自动扫描根。只有在希望把项目级 Skill 提交进 Fork 时，才在克隆完成之后复制：
     mkdir -p $guestWorkspace/.agents/skills && cp -a $GUEST_ATTACHMENTS_SKILLS/$SKILL_NAME $guestWorkspace/.agents/skills/
   - 工作流模板缓存在 $GUEST_WORKFLOW_CACHE（位于克隆目标之外）。官方 main 已包含 $WORKFLOW_RELATIVE；若 Fork 缺少该文件或内容过旧，克隆完成后再复制并提交：
     mkdir -p $guestWorkspace/.github/workflows && cp $GUEST_WORKFLOW_CACHE $guestWorkspace/$WORKFLOW_RELATIVE
   - 编写或调整功能后运行单元测试验证（./gradlew testDebugUnitTest）；
   - 提交修改并推送到 Fork 仓库的特性分支。

5. 通过 GitHub Actions 构建独立的 TianyanDev APK：
   - 构建目标仓库 = $forkLabel；
   - 若该值不是 owner/name，先在 $guestWorkspace 执行 git remote get-url origin，用解析出的 Fork 触发 $WORKFLOW_RELATIVE；
   - 禁止对 $OFFICIAL_REPO_SLUG 执行 workflow_dispatch（contributor 没有权限，会返回 403）；
   - 实时监控构建进度并在成功后下载 APK 至手机；
   - 校验包名 top.tianyan.app.dev 和应用名 TianyanDev，与正式版独立共存。

6. 若体验满意，协助我生成标准 PR 提交到 $OFFICIAL_REPO。"""
    }

    /** 合法 `owner/name` 原样返回；否则返回要求 Agent 读取 `git remote` 的占位符。 */
    fun promptForkLabel(forkRepo: String?): String {
        val trimmed = forkRepo?.trim().orEmpty()
        return if (TianyanDevBuildCli.repoRejection(trimmed) == null) trimmed else FORK_REPO_PLACEHOLDER
    }

    /**
     * 初始化自定义迭代环境与工作区。
     *
     * @param assets 资产读取源（生产环境为 APK assets）
     * @param paths 运行时路径集合（来自 `RuntimePathManager`）
     * @param forkRepo 已解析的 Fork（`owner/name`）。为空时 Prompt 使用占位符，不回退官方仓库
     */
    fun bootstrap(
        assets: AssetReader,
        paths: CustomIterationPaths,
        forkRepo: String? = null,
    ): BootstrapResult {
        val workspace = File(paths.hostHomeDir, WORKSPACE_NAME)
        if (!ensureDirectory(workspace)) {
            return failure("无法创建迭代工作区：${workspace.absolutePath}")
        }

        val missing = mutableListOf<String>()
        return try {
            deploy(assets, ASSET_SKILL, File(paths.attachmentsDir, SKILL_RELATIVE_IN_SCAN_ROOT), missing)
            deploy(assets, ASSET_SKILL, File(paths.workspaceDir, SKILL_RELATIVE_IN_SCAN_ROOT), missing)
            deploy(assets, ASSET_WORKFLOW, File(paths.attachmentsDir, WORKFLOW_CACHE_RELATIVE), missing)

            if (missing.isNotEmpty()) {
                failure("缺少内置资产：${missing.joinToString(", ")}")
            } else {
                BootstrapResult(
                    success = true,
                    workspacePath = workspace.absolutePath,
                    prompt = bootstrapPrompt(GUEST_WORKSPACE, forkRepo),
                )
            }
        } catch (e: Exception) {
            failure(e.message ?: "Bootstrap failed")
        }
    }

    /**
     * 把 assets 中的 [assetPath] 写为 [target]。
     *
     * 内容一致时跳过写入（不改 mtime）；内容不同则覆盖，保证 Skill / 工作流随应用版本升级同步。
     * 模板还会校验关键锚点，防止把空壳或错误文件当成可用工作流。
     */
    private fun deploy(
        assets: AssetReader,
        assetPath: String,
        target: File,
        missing: MutableList<String>,
    ) {
        val bytes = assets.read(assetPath)
        if (bytes == null || bytes.isEmpty()) {
            missing += assetPath
            return
        }
        if (assetPath == ASSET_WORKFLOW && !String(bytes).contains(WORKFLOW_REQUIRED_MARKER)) {
            missing += "$assetPath（缺少 $WORKFLOW_REQUIRED_MARKER 锚点）"
            return
        }
        if (target.isFile && target.length() == bytes.size.toLong()) {
            val existing = runCatching { target.readBytes() }.getOrNull()
            if (existing != null && existing.contentEquals(bytes)) return
        }
        val parent = target.parentFile
        if (parent != null && !ensureDirectory(parent)) {
            missing += "$assetPath（无法创建目录 ${parent.absolutePath}）"
            return
        }
        target.writeBytes(bytes)
    }

    /** 目录已存在则成功；被文件占用或不可写时失败，不把 `false` 当成成功。 */
    private fun ensureDirectory(dir: File): Boolean {
        if (dir.isDirectory) return true
        val created = try {
            dir.mkdirs()
        } catch (e: SecurityException) {
            false
        }
        return created && dir.isDirectory
    }

    private fun failure(message: String) = BootstrapResult(
        success = false,
        workspacePath = "",
        prompt = "",
        errorMessage = message,
    )

    data class BootstrapResult(
        val success: Boolean,
        val workspacePath: String,
        val prompt: String,
        val errorMessage: String? = null,
    )
}
