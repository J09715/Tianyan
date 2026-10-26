package top.tianyan.app.core.model

/**
 * 技能可用性自检，移植自上游 `skill-availability.js`。
 *
 * 为什么要有它：技能正文里写着「本机路径 / 环境变量 / VPS 地址」，但**技能能列出来 ≠ 能跑**。
 * 缺 `FOFA_KEY`、工具没落到 toolkit 目录、VPS 还是占位符，都要等真正动手才发现，
 * 那时候人已经在靶场里了。
 *
 * 判定维度（都是能从技能正文里客观读出来的）：
 *   ① 技能文件是否存在、正文能否加载；
 *   ② 必需环境变量（`os.environ["X"]` / `process.env.X`；带默认值的 `os.environ.get("X", "…")` 不算必需）；
 *   ③ 正文里引用的本机路径（toolkit / bin / local 下的绝对路径、`$DSH_HOME`、`~/`）；
 *   ④ 外部基础设施占位符（如 `<你的VPS_IP>` 没填）。
 *
 * **不判定**：语义正确性、权限、目标可达性 —— 那些只有真打一次才知道。
 *
 * 文件系统与家目录通过 [SkillFs] / 参数注入，让判定逻辑可被确定性地对照测试。
 */
object RedTeamSkillAvailability {

    /** 文件系统探针；生产用真实实现，测试用内存实现。 */
    interface SkillFs {
        fun exists(path: String): Boolean
        fun read(path: String): String?
    }

    data class Skill(
        val name: String,
        val content: String = "",
        val path: String? = null,
        val root: String? = null,
    )

    data class Issue(
        val kind: String,
        val detail: String,
        val fix: String = "",
    )

    data class Checked(
        val file: String? = null,
        val env: List<String> = emptyList(),
        val missingEnv: List<String> = emptyList(),
        val paths: List<String> = emptyList(),
        val missingPaths: List<String> = emptyList(),
    )

    data class Verdict(
        val name: String,
        val status: String,
        val issues: List<Issue>,
        val problems: List<String>,
        val needsUser: List<String>,
        val checked: Checked,
        val shadowedBy: String? = null,
        val shadowedPath: String? = null,
    )

    /** 解析 `$DSH_HOME` / `${DSH_HOME}` / `~`（技能正文里几种写法都有）。 */
    fun expandSkillPath(path: String, dshHome: String, homeDir: String): String {
        var out = path.trim()
        out = out.replace("\${DSH_HOME}", dshHome).replace("\$DSH_HOME", dshHome)
        if (out.startsWith("~/")) out = "$homeDir/${out.removePrefix("~/")}"
        return out
    }

    /**
     * 从技能正文里抽出「必需但没有设」的环境变量名。
     *
     * 三种写法分别处理：
     *   · Node 侧 `process.env.X`（没有默认值这一说）；
     *   · Python 侧 `os.environ["X"]` 必需，`os.environ.get("X", "默认")` 有兜底不算必需；
     *   · shell 侧 `${X:?必填}` 是显式必需；裸 `$X` 不判定（技能里大量出现 `$TARGET` 这类占位）。
     */
    fun requiredEnvOf(content: String?): List<String> {
        val names = linkedSetOf<String>()
        val body = content.orEmpty()
        Regex("process\\.env\\.([A-Z][A-Z0-9_]{2,})").findAll(body).forEach { names += it.groupValues[1] }
        Regex("os\\.environ(?:\\.get)?[\\[(]\\s*[\"']([A-Z][A-Z0-9_]{2,})[\"']\\s*(,)?").findAll(body).forEach { m ->
            // 第二个捕获组存在 = 传了默认值 = 有兜底，不算必需。
            if (m.groupValues[2].isEmpty()) names += m.groupValues[1]
        }
        Regex("\\\$\\{([A-Z][A-Z0-9_]{2,}):\\?").findAll(body).forEach { names += it.groupValues[1] }
        return names.toList()
    }

    /** 正文里引用的、可判定存在性的本机路径（toolkit / bin / local 下的绝对路径）。 */
    fun referencedPathsOf(content: String?): List<String> {
        val out = linkedSetOf<String>()
        Regex("(?:~|\\\$DSH_HOME|/home/[^\\s\"'`,)]+?)/[\\w./\\u4e00-\\u9fa5-]+").findAll(content.orEmpty()).forEach { m ->
            val raw = m.value.replace(Regex("[，。；、：)）\\]]+$"), "")
            if (!Regex("/(toolkit|bin|local)/").containsMatchIn(raw)) return@forEach
            if (Regex("[<>{}*|]").containsMatchIn(raw)) return@forEach
            out += raw
        }
        return out.toList()
    }

    /** 从某个技能根里读同名技能的正文（读不到返回 null）。 */
    fun readSkillFromRoot(root: String?, name: String, fs: SkillFs): Pair<String, String>? {
        if (root.isNullOrEmpty()) return null
        val dirs = listOf(root, "$root/$name")
        for (dir in dirs) {
            for (file in listOf("$dir/$name.md", "$dir/SKILL.md")) {
                val content = fs.read(file)
                if (content != null) return file to content
            }
        }
        return null
    }

    /**
     * 检查一个技能的可用性。
     *
     * @param sameNameIn 其它也注册了同名技能的根目录：同名技能按根顺序择优，排在后面的会被「盖住」。
     *   用它可以兜底识别「实际加载的是随包占位符版本、而你自己配好的版本排在后面」的情况。
     * @return `status` 为 `available`/`broken`/`unknown`；`unknown` 表示正文读不到、判不了。
     */
    fun checkSkill(
        skill: Skill,
        env: Map<String, String> = emptyMap(),
        sameNameIn: List<String> = emptyList(),
        fs: SkillFs,
        dshHome: String = "",
        homeDir: String = "",
        depth: Int = 0,
    ): Verdict {
        val name = skill.name.ifEmpty { "(未命名)" }
        val content = skill.content
        val file = skill.path
        val root = skill.root
        val issues = mutableListOf<Issue>()
        val needsUser = mutableListOf<String>()

        fun addIssue(kind: String, detail: String, extra: Map<String, String> = emptyMap()) {
            issues += Issue(kind, detail, RedTeamFixText.fixesForIssue(kind = kind, skill = name, paths = extra["paths"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(), env = extra["env"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(), placeholder = extra["placeholder"].orEmpty()))
        }

        if (content.trim().isEmpty()) {
            val detail = "技能正文读不到（只有元数据）：无法判断可用性，需要时用 `skill` 工具实际加载一次"
            return Verdict(
                name = name,
                status = "unknown",
                issues = listOf(Issue("content-missing", detail, RedTeamFixText.fixesForIssue(kind = "content-missing", skill = name))),
                problems = listOf(detail),
                needsUser = emptyList(),
                checked = Checked(file = file),
            )
        }

        if (file != null && !fs.exists(file)) addIssue("file-missing", "技能文件不存在：$file", mapOf("file" to file))

        val envNames = requiredEnvOf(content)
        val missingEnv = envNames.filter { env[it].isNullOrEmpty() }
        missingEnv.forEach { addIssue("env-missing", "缺环境变量 $it", mapOf("env" to it)) }

        val paths = referencedPathsOf(content)
        val missingPaths = paths.filter { !fs.exists(expandSkillPath(it, dshHome, homeDir)) }
        if (missingPaths.isNotEmpty()) {
            addIssue(
                "path-missing",
                "引用的本机路径不存在：" + missingPaths.take(6).joinToString("、") +
                    if (missingPaths.size > 6) " 等 ${missingPaths.size} 处" else "",
                mapOf("paths" to missingPaths.joinToString(",")),
            )
        }

        /* 外部基础设施占位符：技能里出现 `<你的VPS_IP>` 这类说明还没配。
           但**已经配好的部署不该被误报**：只要 REDTEAM_VPS_HOST 有值，技能正文里的占位符
           就只是文档写法（实际命令会用配置值替换），不再算缺口。
           判定顺序必须是「先看配置、再看占位符」，否则配好 VPS 的机器上这几个技能会永远显示 broken。 */
        val vpsConfigured = !env["REDTEAM_VPS_HOST"].isNullOrBlank()
        val placeholders = mutableListOf<String>()
        if (!vpsConfigured) {
            if (content.contains("<你的VPS_IP>") || content.contains("<VPS_IP>")) placeholders += "VPS 地址"
            if (content.contains("<你的VPS_主机名>") || content.contains("<VPS 主机名>")) placeholders += "VPS 主机名"
        }
        placeholders.forEach {
            addIssue("placeholder", "${it}还是占位符（技能里写的是占位符，说明本机/本环境还没配）", mapOf("placeholder" to it))
            needsUser += it
        }
        missingEnv.forEach { needsUser += "环境变量 $it" }

        /* 兜底：同名技能在别的根里有一份「问题更少」的版本（通常是用户自己配过的那份，
           但被排在前面的随包占位符版本盖住了）。这时如实说明，而不是让用户以为环境没配。 */
        var shadowedRoot: String? = null
        var shadowedPath: String? = null
        if (depth == 0) {
            for (other in sameNameIn) {
                if (other == root) continue
                val alt = readSkillFromRoot(other, name, fs) ?: continue
                if (alt.second == content) continue
                val verdict = checkSkill(
                    Skill(name = name, content = alt.second, path = alt.first),
                    env = env, sameNameIn = emptyList(), fs = fs,
                    dshHome = dshHome, homeDir = homeDir, depth = depth + 1,
                )
                if (verdict.status == "available" || verdict.problems.size < issues.size) {
                    shadowedRoot = other
                    shadowedPath = alt.first
                    addIssue(
                        "shadowed",
                        "`$other` 里还有一份同名技能（${verdict.status}），" +
                            "但按技能根顺序当前加载的是这一份（上面这些不可用原因来自这一份）",
                        mapOf("other_root" to other),
                    )
                    break
                }
            }
        }

        return Verdict(
            name = name,
            status = if (issues.isEmpty()) "available" else "broken",
            issues = issues,
            problems = issues.map { it.detail },
            needsUser = needsUser.distinct(),
            checked = Checked(
                file = file,
                env = envNames,
                missingEnv = missingEnv,
                paths = paths,
                missingPaths = missingPaths,
            ),
            shadowedBy = shadowedRoot,
            shadowedPath = shadowedPath,
        )
    }

    /** 汇总一批技能的状态（面板顶部标签与 preflight 的返回值都用它）。 */
    fun summarizeSkills(results: List<Verdict>): Map<String, Int> {
        val out = mutableMapOf("total" to results.size, "available" to 0, "broken" to 0, "unknown" to 0)
        results.forEach { r -> out[r.status] = (out[r.status] ?: 0) + 1 }
        return out
    }

    /** 用真实文件系统构造探针。 */
    fun fileSystemFs(): SkillFs = object : SkillFs {
        override fun exists(path: String): Boolean = java.io.File(path).exists()
        override fun read(path: String): String? =
            runCatching { java.io.File(path).takeIf { it.isFile }?.readText() }.getOrNull()
    }
}