package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与上游 `#nucleiIndex` / `templateStats` / `searchTemplates` 的逐例对照测试。
 *
 * 夹具里的输入树与期望值都由跑上游 Node 实现产出，所以这里断的是「同一套检索规则」，
 * 而不是「我以为的检索规则」。
 */
class RedTeamNucleiParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("nuclei_parity.json")) {
            "nuclei_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    /** 用夹具里的输入树重建内存文件系统，避免手抄目录结构。 */
    private class FixtureFs(tree: List<Pair<String, String>>, private val root: String) : RedTeamNucleiIndex.TemplateFs {
        private val files = tree.toMap()
        private val dirs: Set<String> = buildSet {
            tree.forEach { (rel, _) ->
                var acc = ""
                rel.split('/').dropLast(1).forEach { seg ->
                    acc = if (acc.isEmpty()) seg else "$acc/$seg"
                    add("$root/$acc")
                }
            }
            add(root)
        }

        override fun isDirectory(path: String): Boolean = path == root || path in dirs

        override fun list(path: String): List<RedTeamNucleiIndex.Entry> {
            val prefix = "$path/"
            val direct = mutableSetOf<String>()
            files.keys.forEach { rel ->
                val full = "$root/$rel"
                if (!full.startsWith(prefix)) return@forEach
                val rest = full.removePrefix(prefix)
                if (rest.isEmpty()) return@forEach
                direct += rest.substringBefore('/')
            }
            return direct.map { name ->
                RedTeamNucleiIndex.Entry(name, isDirectory("$path/$name"))
            }.sortedBy { it.name }
        }

        override fun readHead(path: String, maxChars: Int): String? =
            files[path.removePrefix("$root/")]?.take(maxChars)
    }

    private fun build(): Pair<RedTeamNucleiIndex.Index, String> {
        val tree = fixture["tree"]!!.jsonArray.map {
            val row = it.jsonObject
            row["path"]!!.jsonPrimitive.content to row["content"]!!.jsonPrimitive.content
        }
        val root = "/tmp/nuclei-fixture/toolkit/nuclei-templates"
        val fs = FixtureFs(tree, root)
        return RedTeamNucleiIndex.Index(root, RedTeamNucleiIndex.buildIndex(root, fs).toList()) to root
    }

    @Test
    fun `template index matches upstream`() {
        val (index, root) = build()
        val expected = fixture["stats"]!!.jsonObject
        val stats = RedTeamNucleiIndex.stats(index)
        assertEquals(expected["total"]!!.jsonPrimitive.content.toInt(), stats.total, "total templates")
        assertEquals(expected["cve"]!!.jsonPrimitive.content.toInt(), stats.cve, "cve templates")
        assertEquals(root, stats.dir)
        // 非 yaml 文件不进索引。
        assertTrue(index.items.none { it.path.endsWith(".txt") }, "non-yaml must be skipped")
    }

    /** 引号必须剥掉，否则检索结果里会带引号、界面上看起来像脏数据。 */
    @Test
    fun `yaml quoted values are unquoted`() {
        val (index, _) = build()
        val quoted = index.items.first { it.path.contains("quoted") }
        assertEquals("Quoted Name", quoted.name)
        assertEquals("low", quoted.severity)
        assertEquals("a,b", quoted.tags)
    }

    @Test
    fun `template search matches upstream`() {
        val (index, _) = build()
        val searches = fixture["searches"]!!.jsonArray.map { it.jsonObject }
        searches.forEach { row ->
            val query = row["q"]!!.jsonPrimitive.content
            val expected = row["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            val actual = RedTeamNucleiIndex.search(index, query).items.map { it.name }
            assertEquals(expected, actual, "search(\"$query\")")
            // total 是库内总量，不随查询变化。
            assertEquals(
                row["total"]!!.jsonPrimitive.content.toInt(),
                RedTeamNucleiIndex.search(index, query).total,
                "total@\"$query\"",
            )
        }
    }

    /** CVE 编号按文件名命中：这是「本机已有现成 POC」的主路径。 */
    @Test
    fun `cve search hits the file name`() {
        val (index, _) = build()
        val hits = RedTeamNucleiIndex.search(index, "cve-2021").items
        assertEquals(1, hits.size)
        assertTrue(hits.single().path.contains("CVE-2021-44228.yaml"))
    }

    /** 组件关键字走 name 与 tags。 */
    @Test
    fun `component keyword matches name and tags`() {
        val (index, _) = build()
        assertTrue(RedTeamNucleiIndex.search(index, "log4j").items.isNotEmpty(), "tag match")
        assertTrue(RedTeamNucleiIndex.search(index, "redis").items.isNotEmpty(), "name match")
        assertTrue(RedTeamNucleiIndex.search(index, "nonexistent-xyz").items.isEmpty())
    }

    /** 空查询返回 total 但不返回条目：界面据此显示库内总量而不刷全量列表。 */
    @Test
    fun `empty query reports the total without listing everything`() {
        val (index, _) = build()
        val result = RedTeamNucleiIndex.search(index, "")
        assertEquals(0, result.items.size)
        assertEquals(5, result.total)
        assertEquals(0, RedTeamNucleiIndex.search(index, "   ").items.size)
    }

    @Test
    fun `search respects the limit`() {
        val (index, _) = build()
        val limited = RedTeamNucleiIndex.search(index, "cve", 1)
        assertEquals(1, limited.items.size)
        // 上限硬约束 200，防止界面传个巨大值把响应撑爆。
        assertEquals(2, RedTeamNucleiIndex.search(index, "cve", 10_000).items.size)
    }

    /** 没装模板库时检索是空的，而不是报错。 */
    @Test
    fun `missing template library yields an empty result`() {
        val index = RedTeamNucleiIndex.Index(null, emptyList())
        val result = RedTeamNucleiIndex.search(index, "cve")
        assertNull(result.dir)
        assertEquals(0, result.total)
        assertTrue(result.items.isEmpty())
        assertEquals(0, RedTeamNucleiIndex.stats(index).total)
    }

    /** 候选目录顺序：先靶标自带的 toolkit，再用户目录，最后系统目录。 */
    @Test
    fun `template dir resolution follows the candidate order`() {
        val root = "/srv/redteam"
        val home = "/root"
        assertEquals(
            listOf(
                "/srv/redteam/toolkit/nuclei-templates",
                "/root/.local/nuclei-templates",
                "/root/nuclei-templates",
                "/usr/share/nuclei-templates",
                "/opt/nuclei-templates",
            ),
            RedTeamNucleiIndex.candidates(root, home),
        )

        // 只有系统目录存在 → 用它。
        val onlySystem = object : RedTeamNucleiIndex.TemplateFs {
            override fun isDirectory(path: String) = path == "/opt/nuclei-templates"
            override fun list(path: String) = emptyList<RedTeamNucleiIndex.Entry>()
            override fun readHead(path: String, maxChars: Int): String? = null
        }
        assertEquals("/opt/nuclei-templates", RedTeamNucleiIndex.pickDir(root, home, onlySystem))

        // toolkit 优先于系统目录。
        val both = object : RedTeamNucleiIndex.TemplateFs {
            override fun isDirectory(path: String) = path == "/opt/nuclei-templates" || path == "$root/toolkit/nuclei-templates"
            override fun list(path: String) = emptyList<RedTeamNucleiIndex.Entry>()
            override fun readHead(path: String, maxChars: Int): String? = null
        }
        assertEquals("$root/toolkit/nuclei-templates", RedTeamNucleiIndex.pickDir(root, home, both))

        val none = object : RedTeamNucleiIndex.TemplateFs {
            override fun isDirectory(path: String) = false
            override fun list(path: String) = emptyList<RedTeamNucleiIndex.Entry>()
            override fun readHead(path: String, maxChars: Int): String? = null
        }
        assertNull(RedTeamNucleiIndex.pickDir(root, home, none))
    }

    /** 缓存：目录一致且在 7 天内才复用；目录变了必须重建。 */
    @Test
    fun `index cache expires and invalidates on directory change`() {
        val now = 1_000_000_000L
        assertTrue(RedTeamNucleiIndex.cacheFresh("/a", now - 1000, "/a", now))
        assertFalse(RedTeamNucleiIndex.cacheFresh("/a", now - RedTeamNucleiIndex.CACHE_TTL_MILLIS - 1, "/a", now))
        assertFalse(RedTeamNucleiIndex.cacheFresh("/a", now - 1000, "/b", now))
        assertFalse(RedTeamNucleiIndex.cacheFresh(null, now - 1000, "/a", now))
    }

    /** 索引只读文件头：超大模板不该被整份读进来。 */
    @Test
    fun `index only reads the head of each template`() {
        val root = "/t"
        val body = "info:\n  name: Big\n  severity: high\n" + "x".repeat(50_000)
        val fs = object : RedTeamNucleiIndex.TemplateFs {
            override fun isDirectory(path: String) = true
            override fun list(path: String) = listOf(RedTeamNucleiIndex.Entry("big.yaml", false))
            override fun readHead(path: String, maxChars: Int): String? = body.take(maxChars)
        }
        val items = RedTeamNucleiIndex.buildIndex(root, fs)
        assertEquals("Big", items.single().name)
        assertNotNull(RedTeamNucleiIndex.stats(RedTeamNucleiIndex.Index(root, items)).dir)
    }
}