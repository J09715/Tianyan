package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 与上游 `skill-availability.js` / `skill-fixes.js` 的逐例对照测试。
 *
 * 「能列出来 ≠ 能跑」：缺 `FOFA_KEY`、工具没落到 toolkit、VPS 还是占位符，
 * 都要等真正动手才发现，那时候人已经在靶场里了。判定与修复文案都必须与上游一致，
 * 否则面板给出的修复步骤会把用户引到错误的方向。
 */
class RedTeamSkillAvailabilityParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("skill_parity.json")) {
            "skill_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    private fun rows(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }

    private fun JsonElement?.text(): String? = this?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonElement?.strings(): List<String> = this?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    @Test
    fun `required env extraction matches upstream`() {
        rows("requiredEnv").forEach { row ->
            val content = row["content"]!!.jsonPrimitive.content
            assertEquals(
                row["value"].strings(),
                RedTeamSkillAvailability.requiredEnvOf(content),
                "requiredEnvOf(\"$content\")",
            )
        }
    }

    /** 带默认值的 `os.environ.get("X", "d")` 有兜底，不算必需。 */
    @Test
    fun `env with a default value is not required`() {
        assertEquals(listOf("MY_KEY"), RedTeamSkillAvailability.requiredEnvOf("""os.environ["MY_KEY"] and os.environ.get("OPT", "d")"""))
        assertEquals(listOf("A_KEY"), RedTeamSkillAvailability.requiredEnvOf("""os.environ["A_KEY"] ; os.environ.get("B_KEY", "x")"""))
    }

    /** 裸 `$X` 不判定：技能里大量出现 `$TARGET` 这类占位。 */
    @Test
    fun `bare shell variables are not treated as required`() {
        assertEquals(listOf("REQ_VAR"), RedTeamSkillAvailability.requiredEnvOf("\${REQ_VAR:?must} and \$OPTIONAL"))
    }

    @Test
    fun `referenced path extraction matches upstream`() {
        rows("referencedPaths").forEach { row ->
            val content = row["content"]!!.jsonPrimitive.content
            assertEquals(
                row["value"].strings(),
                RedTeamSkillAvailability.referencedPathsOf(content),
                "referencedPathsOf(\"$content\")",
            )
        }
    }

    @Test
    fun `skill path expansion matches upstream`() {
        rows("expandPaths").forEach { row ->
            assertEquals(
                row["value"]!!.jsonPrimitive.content,
                RedTeamSkillAvailability.expandSkillPath(
                    row["path"]!!.jsonPrimitive.content,
                    dshHome = row["dshHome"]!!.jsonPrimitive.content,
                    homeDir = row["home"]!!.jsonPrimitive.content,
                ),
                "expandSkillPath(${row["path"]})",
            )
        }
    }

    @Test
    fun `tool name extraction matches upstream`() {
        assertEquals("nmap", RedTeamFixText.toolNameOfPath("/root/.dsh/redteam/toolkit/nmap/nmap"))
        // toolkit 后第一段就是工具名，文件名不参与。
        assertEquals("fscan", RedTeamFixText.toolNameOfPath("/a/toolkit/fscan/fscan_linux_amd64"))
        assertEquals("chisel", RedTeamFixText.toolNameOfPath("/a/toolkit/chisel/chisel_1.9.1_linux_amd64"))
        assertEquals("x", RedTeamFixText.toolNameOfPath("/a/toolkit/x/tool.exe"))
        assertEquals("thing", RedTeamFixText.toolNameOfPath("/usr/bin/thing"))
        assertEquals("thing2", RedTeamFixText.toolNameOfPath("/home/u/.local/bin/thing2"))
        assertEquals("thing", RedTeamFixText.toolNameOfPath("thing"))
        assertEquals("naabu", RedTeamFixText.toolNameOfPath("/a/toolkit/naabu/naabu，"))
        // 没有任何字母 → null，不编造工具名。
        assertEquals(null, RedTeamFixText.toolNameOfPath("/a/toolkit/123/456"))
        assertEquals(null, RedTeamFixText.toolNameOfPath(""))
        assertEquals(null, RedTeamFixText.toolNameOfPath("/opt/x/Windows/tool"))
    }

    @Test
    fun `fix text matches upstream`() {
        rows("fixes").forEach { row ->
            val input = row["input"]!!.jsonObject
            val actual = RedTeamFixText.fixesForIssue(
                kind = input["kind"].text().orEmpty(),
                skill = input["skill"].text().orEmpty(),
                paths = input["paths"].strings(),
                env = input["env"]?.let { el ->
                    if (el is JsonArray) el.map { it.jsonPrimitive.content } else listOfNotNull(el.text())
                } ?: emptyList(),
                placeholder = input["placeholder"].text().orEmpty(),
            )
            assertEquals(row["value"]!!.jsonPrimitive.content, actual, "fixesForIssue($input)")
        }
    }

    /** 同一个工具只列一次：impacket 有十几个脚本，逐条列会把面板撑爆。 */
    @Test
    fun `impacket aliases collapse into one fix entry`() {
        val text = RedTeamFixText.fixesForIssue(
            kind = "path-missing",
            paths = listOf("/a/toolkit/impacket-wmiexec/x", "/a/toolkit/impacket-secretsdump/y"),
        )
        assertTrue(text.contains("缺 1 个工具"), "aliases must collapse: $text")
    }

    @Test
    fun `skill summarization matches upstream`() {
        rows("summarize").forEach { row ->
            val statuses = row["input"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
            val actual = RedTeamSkillAvailability.summarizeSkills(
                statuses.map { RedTeamSkillAvailability.Verdict(it, it, emptyList(), emptyList(), emptyList(), RedTeamSkillAvailability.Checked()) },
            )
            val expected = row["value"]!!.jsonObject
            assertEquals(expected["total"]!!.jsonPrimitive.content.toInt(), actual["total"], "total@${row["input"]}")
            listOf("available", "broken", "unknown").forEach { k ->
                val want = expected[k].text()?.toIntOrNull() ?: 0
                assertEquals(want, actual[k] ?: 0, "$k@${row["input"]}")
            }
        }
    }

    private class MemoryFs(private val files: Map<String, String>) : RedTeamSkillAvailability.SkillFs {
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): String? = files[path]
    }

    @Test
    fun `missing content yields unknown rather than broken`() {
        val verdict = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(name = "active-scan", content = ""),
            fs = MemoryFs(emptyMap()),
            dshHome = "/root/.dsh",
            homeDir = "/root",
        )
        assertEquals("unknown", verdict.status)
        assertEquals("content-missing", verdict.issues.single().kind)
    }

    @Test
    fun `available skill reports no issues`() {
        val fs = MemoryFs(mapOf("/root/.dsh/redteam/toolkit/nmap/nmap" to "bin"))
        val verdict = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(
                name = "active-scan",
                content = "run \$DSH_HOME/redteam/toolkit/nmap/nmap -sV",
            ),
            env = emptyMap(),
            fs = fs,
            dshHome = "/root/.dsh",
            homeDir = "/root",
        )
        assertEquals("available", verdict.status, "issues: ${verdict.problems}")
    }

    @Test
    fun `missing tool and env produce broken with fix text`() {
        val verdict = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(
                name = "recon-passive",
                content = "use process.env.FOFA_KEY and run \$DSH_HOME/redteam/toolkit/nmap/nmap",
            ),
            env = emptyMap(),
            fs = MemoryFs(emptyMap()),
            dshHome = "/root/.dsh",
            homeDir = "/root",
        )
        assertEquals("broken", verdict.status)
        assertTrue(verdict.issues.any { it.kind == "env-missing" }, "should flag the env var")
        assertTrue(verdict.issues.any { it.kind == "path-missing" }, "should flag the missing tool")
        assertTrue(verdict.needsUser.any { it.contains("FOFA_KEY") }, "needs_user must name the variable")
        assertTrue(verdict.issues.all { it.fix.isNotEmpty() }, "every issue must carry a fix")
    }

    /**
     * 配好 VPS 的机器上，正文里的占位符只是文档写法，不该再报 broken——
     * 否则这几个技能会永远显示不可用（模板占位符与真实配置混在一起）。
     */
    @Test
    fun `configured vps suppresses placeholder issues`() {
        val content = "ssh root@<你的VPS_IP>"
        val withoutVps = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(name = "tunnel-frp", content = content),
            env = emptyMap(),
            fs = MemoryFs(emptyMap()),
            dshHome = "/root/.dsh", homeDir = "/root",
        )
        assertTrue(withoutVps.issues.any { it.kind == "placeholder" }, "should flag unconfigured VPS")

        val withVps = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(name = "tunnel-frp", content = content),
            env = mapOf("REDTEAM_VPS_HOST" to "ubuntu@203.0.113.10"),
            fs = MemoryFs(emptyMap()),
            dshHome = "/root/.dsh", homeDir = "/root",
        )
        assertTrue(withVps.issues.none { it.kind == "placeholder" }, "configured VPS must not be flagged: ${withVps.problems}")
    }

    /** 同名技能被前面的根盖住时要如实说明，而不是让用户以为环境没配。 */
    @Test
    fun `shadowed skill is reported with the other root`() {
        val fs = MemoryFs(
            mapOf(
                "/later/active-scan.md" to "run \$DSH_HOME/redteam/toolkit/nmap/nmap",
                "/later/redteam/toolkit/nmap/nmap" to "bin",
            ),
        )
        val verdict = RedTeamSkillAvailability.checkSkill(
            skill = RedTeamSkillAvailability.Skill(
                name = "active-scan",
                content = "missing everything \$DSH_HOME/redteam/toolkit/nonexistent/x",
                path = "/first/active-scan.md",
                root = "/first",
            ),
            sameNameIn = listOf("/later"),
            fs = fs,
            dshHome = "/root/.dsh",
            homeDir = "/root",
        )
        assertEquals("/later", verdict.shadowedBy, "should name the shadowing root: ${verdict.problems}")
        assertTrue(verdict.issues.any { it.kind == "shadowed" })
    }
}