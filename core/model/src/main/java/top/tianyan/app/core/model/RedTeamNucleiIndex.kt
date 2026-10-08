package top.tianyan.app.core.model

/**
 * 本机 nuclei 模板库索引，移植自上游 `nucleiTemplatesDir` / `#nucleiIndex` /
 * `templateStats` / `searchTemplates`。
 *
 * 这一层的意义是「知识库里没有、但本机其实已有现成 POC」——先查它能省掉一轮互联网检索。
 * 13k 个 yaml 逐个完整解析太慢，所以只抽 `path / name / severity / tags` 四个检索字段。
 *
 * 文件系统通过 [TemplateFs] 注入：索引构建是纯逻辑，但读盘要有接缝，否则只能连真实模板目录一起测。
 */
object RedTeamNucleiIndex {

    /** 上游只读文件头 2000 字符——yaml 的 info 段都在前面，读全文件纯属浪费。 */
    const val HEAD_CHARS = 2000

    const val DEFAULT_LIMIT = 40
    const val MAX_LIMIT = 200

    /** 缓存有效期：目录没变且不超过 7 天就直接复用。 */
    const val CACHE_TTL_MILLIS = 7L * 24 * 3600 * 1000

    data class Entry(val name: String, val isDirectory: Boolean)

    /** 读盘接缝。 */
    interface TemplateFs {
        fun isDirectory(path: String): Boolean
        fun list(path: String): List<Entry>
        fun readHead(path: String, maxChars: Int): String?
    }

    /** `path` 是相对模板库根目录的路径，`name`/`severity`/`tags` 是 yaml 里抽出来的检索字段。 */
    data class Template(val path: String, val name: String, val severity: String, val tags: String)

    data class Index(val dir: String?, val items: List<Template>)

    data class Stats(val dir: String?, val total: Int, val cve: Int)

    data class SearchResult(val dir: String?, val total: Int, val items: List<Template>)

    /** 候选模板库位置，按上游顺序——先看靶标自带的 toolkit，再看用户目录，最后看系统目录。 */
    fun candidates(root: String, homeDir: String): List<String> = listOf(
        "$root/toolkit/nuclei-templates",
        "$homeDir/.local/nuclei-templates",
        "$homeDir/nuclei-templates",
        "/usr/share/nuclei-templates",
        "/opt/nuclei-templates",
    )

    /** 第一个存在的候选目录；都没装返回 null（此时检索结果是空的，而不是报错）。 */
    fun pickDir(root: String, homeDir: String, fs: TemplateFs): String? =
        candidates(root, homeDir).firstOrNull { fs.isDirectory(it) }

    /**
     * 递归收集 `.yaml` / `.yml` 的检索字段。
     *
     * 抽字段的正则按行锚定并取**首个**匹配：yaml 里 `info:` 缩进下的 name/severity/tags
     * 都在文件前部，取首个即可；值上的引号要剥掉（`name: "x"` → `x`）。
     */
    fun buildIndex(dir: String, fs: TemplateFs): List<Template> {
        val out = mutableListOf<Template>()
        fun walk(rel: String) {
            val base = if (rel.isEmpty()) dir else "$dir/$rel"
            fs.list(base).forEach { entry ->
                val next = if (rel.isEmpty()) entry.name else "$rel/${entry.name}"
                if (entry.isDirectory) {
                    walk(next)
                    return@forEach
                }
                if (!Regex("\\.ya?ml$", RegexOption.IGNORE_CASE).containsMatchIn(entry.name)) return@forEach
                val head = fs.readHead("$dir/$next", HEAD_CHARS) ?: return@forEach
                fun pick(pattern: String): String =
                    Regex(pattern, setOf(RegexOption.MULTILINE)).find(head)?.groupValues?.get(1)
                        ?.trim()?.trim('"', '\'') ?: ""
                out += Template(
                    path = next,
                    name = pick("""^\s*name:\s*(.+)$"""),
                    severity = pick("""^\s*severity:\s*(.+)$"""),
                    tags = pick("""^\s*tags:\s*(.+)$"""),
                )
            }
        }
        walk("")
        return out
    }

    fun stats(index: Index): Stats {
        val cve = index.items.count { Regex("cve-\\d{4}-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(it.path) }
        return Stats(index.dir, index.items.size, cve)
    }

    /**
     * 在本机模板库里检索：CVE 编号按文件名就能命中，组件/关键字再匹配 name 与 tags。
     *
     * 空查询返回 `total` 但不返回条目——上游如此，界面据此显示「库里有 N 个模板」而不用刷全量列表。
     */
    fun search(index: Index, query: String?, limit: Int = DEFAULT_LIMIT): SearchResult {
        if (index.dir == null) return SearchResult(null, 0, emptyList())
        val raw = query?.trim().orEmpty()
        if (raw.isEmpty()) return SearchResult(index.dir, index.items.size, emptyList())
        val needle = raw.lowercase()
        val capped = limit.coerceAtMost(MAX_LIMIT)
        val out = mutableListOf<Template>()
        for (item in index.items) {
            val hay = (item.path + " " + item.name + " " + item.tags).lowercase()
            if (hay.contains(needle)) out += item
            if (out.size >= capped) break
        }
        return SearchResult(index.dir, index.items.size, out)
    }

    /** 缓存是否还能用：目录一致且在有效期内。 */
    fun cacheFresh(cachedDir: String?, cachedBuiltAt: Long, currentDir: String, now: Long): Boolean =
        cachedDir == currentDir && currentDir.isNotEmpty() && (now - cachedBuiltAt) < CACHE_TTL_MILLIS
}