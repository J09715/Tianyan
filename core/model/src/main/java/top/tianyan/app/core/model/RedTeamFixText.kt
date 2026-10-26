package top.tianyan.app.core.model

/**
 * 把可用性判定翻译成可执行的中文修复步骤，移植自上游 `skill-fixes.js` 的 `fixesForIssue`。
 *
 * 面板上不只是说「哪里不可用」，而是直接告诉用户「怎么修」。
 * 文案表见 [RedTeamSkillFixes]（由上游程序化生成）。
 */
object RedTeamFixText {

    /**
     * 由 [RedTeamSkillAvailability] 的判定生成一条中文修复建议。
     *
     * @param kind `file-missing` / `content-missing` / `env-missing` / `path-missing` / `placeholder` / `shadowed`
     * @param paths `path-missing` 时缺失的路径列表
     * @param env `env-missing` 时缺失的环境变量名列表
     * @return 一条中文建议（命令都在反引号里）。拿不准时回退到技能专属文案，再回退到通用建议。
     */
    fun fixesForIssue(
        kind: String,
        skill: String = "",
        paths: List<String> = emptyList(),
        env: List<String> = emptyList(),
        placeholder: String = "",
    ): String {
        val skillName = skill.ifEmpty { "<技能名>" }
        val skillHint = RedTeamSkillFixes.SKILL_FIXES[skill].orEmpty()
        val parts = mutableListOf<String>()

        when (kind) {
            "path-missing" -> {
                val tools = mutableListOf<Triple<String, String, String>>() // name, canon, path
                val seen = mutableSetOf<String>()
                paths.filter { it.isNotBlank() }.forEach { p ->
                    val name = toolNameOfPath(p) ?: return@forEach
                    val canon = RedTeamSkillFixes.canonicalTool(name)
                    // 同一个工具只列一次：impacket 有十几个脚本，逐条列会把面板撑爆。
                    if (!seen.add(canon)) return@forEach
                    tools += Triple(name, canon, p)
                }
                if (tools.isNotEmpty()) {
                    val items = tools.joinToString("；") { (name, canon, path) ->
                        val fix = RedTeamSkillFixes.TOOL_FIXES[canon]
                        if (fix == null) {
                            "`$name`（$path）：本机没有这个工具，跑 `${RedTeamSkillFixes.SETUP}` 补齐；" +
                                "仍缺就从该工具官方发布页取对应平台二进制放到这个路径并 `chmod +x`"
                        } else {
                            "`$name`（$path）：${fix.what}。修法：${fix.install}"
                        }
                    }
                    parts += "缺 ${tools.size} 个工具，逐条修：$items"
                    parts += "拿不准就先统一跑 `${RedTeamSkillFixes.SETUP}`（幂等：缺的下载、损坏的删掉重下），再重新体检。"
                }
            }

            "env-missing" -> {
                val names = env.filter { it.isNotBlank() }
                if (names.isNotEmpty()) {
                    val items = names.joinToString("；") { n ->
                        val hint = RedTeamSkillFixes.ENV_HINTS[n] ?: "值由你自己提供"
                        "`$n`：`export $n=<值>`（$hint）"
                    }
                    parts += "缺环境变量：$items。"
                    parts += "持久化：写进 `${'$'}DSH_HOME/.env`（一行一个 `NAME=值`，建议 `chmod 600`），" +
                        "改完必须重启 `dsh web` 当前进程才读得到；或跑 `${RedTeamSkillFixes.SETUP}` 由脚本代写" +
                        "（`FOFA_KEY`、VPS 相关变量它都会引导并当场校验）。"
                }
            }

            "placeholder" -> {
                val ph = placeholder.trim().ifEmpty { "外部基础设施地址" }
                parts += "技能正文里 `$ph` 还是占位符（说明本环境还没配 VPS）。"
                parts += "填法：跑 `${RedTeamSkillFixes.SETUP}` 引导写入 `REDTEAM_VPS_HOST`（`用户@主机`）与 " +
                    "`REDTEAM_VPS_KEY`（私钥路径，默认 `${'$'}DSH_HOME/redteam/toolkit/vps/id_rsa`）；" +
                    "也可手动写 `${'$'}DSH_HOME/.env` 后重启 `dsh web`，或直接编辑 `${'$'}DSH_HOME/skills/$skillName.md`，" +
                    "把占位符替换成真实地址（私钥记得 `chmod 600`）。"
            }

            "shadowed" -> {
                parts += "技能被同名版本盖住了：`$skillName` 在别的技能根里还有一份（配置更全）的同名技能，" +
                    "但按当前技能根顺序加载的是排在前面那份不完整的。"
                parts += "修法：把已配置的那个技能根排到技能根顺序的最前面（插件/技能目录设置里调整顺序），" +
                    "重启 `dsh web` 后重新体检；不需要另一份的话把它删掉或改个名，避免再次盖住。"
            }

            "file-missing" -> {
                parts += "技能文件不存在：重新安装 redteam 插件（或把技能目录挂回技能根）覆盖技能文件，然后重启 `dsh web` 再体检一次。"
                parts += "若是自己放的技能，确认文件在技能根下且文件名与技能名一致（`<技能名>.md`）。"
            }

            "content-missing" -> {
                parts += "技能正文读不到（只有元数据，判不了可用性）：重新安装 redteam 插件覆盖技能文件；" +
                    "若是远端技能源，确认源可达后重新拉取，再重启 `dsh web`。"
                parts += "临时要确认能不能用，直接让智能体用 `skill` 工具实际加载一次 `$skillName`。"
            }
        }

        if (parts.isEmpty()) parts += skillHint.ifEmpty { RedTeamSkillFixes.GENERIC_FIX }
        return parts.joinToString(" ")
    }

    /**
     * 从路径反推工具名。
     * `…/toolkit/nmap/nmap` → `nmap`；`/usr/bin/thing` → `thing`；
     * 认不出返回 null（不编造工具名）。
     */
    fun toolNameOfPath(path: String?): String? {
        val s = path?.trim().orEmpty()
            .trim('"', '\'', '`')
            .replace(Regex("[，。；、：,;:)\\]}>）】]+$"), "")
            .replace('\\', '/')
        if (s.isEmpty()) return null
        var candidate: String? = null
        val at = s.lastIndexOf("toolkit/")
        if (at != -1) {
            candidate = s.substring(at + "toolkit/".length).split('/').firstOrNull { it.isNotEmpty() }
        } else if (Regex("/bin/").containsMatchIn(s) || Regex("(^|/)\\.local/").containsMatchIn(s)) {
            val segs = s.split('/').filter { it.isNotEmpty() }
            candidate = segs.lastOrNull()
        } else if (!s.contains('/')) {
            candidate = s
        }
        return candidate?.let { cleanToolName(it) }
    }

    /**
     * 工具名清理：剥扩展名，再反复剥平台/架构后缀，最后转小写。
     * 没有任何字母（例如纯数字目录）返回 null —— 不编造工具名。
     */
    private fun cleanToolName(raw: String?): String? {
        var out = raw?.trim().orEmpty()
        if (out.isEmpty()) return null
        out = out.replace(EXT_RE, "")
        var prev = ""
        while (out.isNotEmpty() && out != prev) {
            prev = out
            out = out.replace(PLATFORM_RE, "").replace(ARCH_RE, "")
        }
        out = out.lowercase()
        return out.takeIf { it.any { c -> c in 'a'..'z' } }
    }

    private val EXT_RE = Regex("\\.(exe|zip|tar\\.gz|tgz|gz|bz2|xz|jar|sh|py|bin|txt|md|json)$", RegexOption.IGNORE_CASE)
    private val PLATFORM_RE = Regex("[-_](windows|win32|win64|win|linux|darwin|macos|osx|freebsd)[-_][a-z0-9_.]+$")
    private val ARCH_RE = Regex("[-_](amd64|x86_64|x64|arm64|aarch64|arm|386|i386|i686|mips[a-z0-9]*)$")
}