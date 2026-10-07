package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与上游 `validate.js` / `settings.js` 的逐例对照测试。
 *
 * 这两块是安全边界与最常调的能力：路径穿越判定错了就是把 `/etc/passwd` 读出来，
 * 并发上限算错了就是「我改了没生效」。所以逐例比对上游客吐值。
 */
class RedTeamValidateParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("validate_parity.json")) {
            "validate_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    private fun rows(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        this?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    @Test
    fun `shell type normalization matches upstream`() {
        rows("shellTypes").forEach { row ->
            val input = row["input"].text()
            val ok = row["ok"]!!.jsonPrimitive.content.toBoolean()
            if (ok) {
                assertEquals(row["value"].text(), RedTeamValidate.normalizeShellType(input), "normalizeShellType($input)")
            } else {
                // 认不出必须报错而不是静默存原文：存错值不会报错，只会让面板的两处判定静默失效。
                assertFailsWith<IllegalArgumentException>("normalizeShellType($input) must throw") {
                    RedTeamValidate.normalizeShellType(input)
                }
            }
        }
    }

    @Test
    fun `chinese aliases map to canonical shell types`() {
        assertEquals("behinder", RedTeamValidate.normalizeShellType("冰蝎"))
        assertEquals("godzilla", RedTeamValidate.normalizeShellType("哥斯拉"))
        assertEquals("antSword", RedTeamValidate.normalizeShellType("蚁剑"))
        assertEquals("custom", RedTeamValidate.normalizeShellType("自研"))
        assertNull(RedTeamValidate.normalizeShellType(""))
        assertNull(RedTeamValidate.normalizeShellType(null))
    }

    @Test
    fun `shell status normalization matches upstream`() {
        rows("shellStatuses").forEach { row ->
            val input = row["input"].text()
            val ok = row["ok"]!!.jsonPrimitive.content.toBoolean()
            if (ok) {
                assertEquals(row["value"].text(), RedTeamValidate.normalizeShellStatus(input), "normalizeShellStatus($input)")
            } else {
                assertFailsWith<IllegalArgumentException>("normalizeShellStatus($input) must throw") {
                    RedTeamValidate.normalizeShellStatus(input)
                }
            }
        }
        // 空值默认 online —— 面板按三态显示，空值不该变成第四态。
        assertEquals("online", RedTeamValidate.normalizeShellStatus(""))
        assertEquals("online", RedTeamValidate.normalizeShellStatus("up"))
        assertEquals("dead", RedTeamValidate.normalizeShellStatus("removed"))
    }

    @Test
    fun `path guard matches upstream`() {
        rows("pathGuards").forEach { row ->
            val target = row["target"].text().orEmpty()
            val roots = row["roots"]!!.jsonArray.map { it.jsonPrimitive.content }
            val ok = row["ok"]!!.jsonPrimitive.content.toBoolean()
            if (ok) {
                val actual = RedTeamValidate.assertPathWithin(target, roots)
                assertTrue(actual.isNotEmpty(), "assertPathWithin($target) should return a path")
            } else {
                assertFailsWith<IllegalArgumentException>("assertPathWithin(\"$target\") must be rejected") {
                    RedTeamValidate.assertPathWithin(target, roots)
                }
            }
        }
    }

    /** 这条是核心安全断言：库里被智能体写入的路径不可信，越界读取必须被拦住。 */
    @Test
    fun `traversal and outside paths are refused`() {
        val roots = listOf("/srv/redteam/eng1")
        assertFailsWith<IllegalArgumentException> {
            RedTeamValidate.assertPathWithin("/srv/redteam/eng1/../../etc/passwd", roots)
        }
        assertFailsWith<IllegalArgumentException> {
            RedTeamValidate.assertPathWithin("/etc/passwd", roots)
        }
        assertFailsWith<IllegalArgumentException> {
            RedTeamValidate.assertPathWithin("~/.ssh/id_rsa", roots)
        }
        // 前缀相近但不是子目录的必须拒绝：/srv/redteam/eng1x 不是 eng1 的子目录。
        assertFailsWith<IllegalArgumentException> {
            RedTeamValidate.assertPathWithin("/srv/redteam/eng1x/file.txt", roots)
        }
        // 内部的 .. 归一化后仍在根内 → 允许。
        assertEquals("/srv/redteam/eng1/file.txt", RedTeamValidate.assertPathWithin("/srv/redteam/eng1/sub/../file.txt", roots))
    }

    @Test
    fun `pathWithinOrNull degrades to not found instead of throwing at the ui`() {
        assertNull(RedTeamValidate.pathWithinOrNull("/etc/passwd", listOf("/srv/redteam/eng1")))
        assertEquals("/srv/redteam/eng1/a.txt", RedTeamValidate.pathWithinOrNull("/srv/redteam/eng1/a.txt", listOf("/srv/redteam/eng1")))
    }

    @Test
    fun `max agents clamping matches upstream`() {
        rows("maxAgents").forEach { row ->
            val input = row["input"]!!.jsonPrimitive.content
            val expected = row["value"]!!.jsonPrimitive.content.toInt()
            val actual = RedTeamSettings.clampMaxAgents(parseLikeJs(input))
            assertEquals(expected, actual, "clampMaxAgents($input)")
        }
    }

    /** 夹具里的输入是 `String(v)`，这里还原成同类型的值，避免把 `"3"` 与 `3` 混为一谈。 */
    private fun parseLikeJs(raw: String): Any? = when (raw) {
        "null" -> null
        "undefined" -> "undefined"
        "NaN" -> Double.NaN
        else -> raw.toDoubleOrNull() ?: raw
    }

    /** 两个容易搞反的边界。 */
    @Test
    fun `null clamps to one while unparseable falls back to default`() {
        // JS Number(null) === 0 → 收敛到 1，不是默认值 3。
        assertEquals(1, RedTeamSettings.clampMaxAgents(null))
        assertEquals(3, RedTeamSettings.clampMaxAgents(Double.NaN))
        assertEquals(3, RedTeamSettings.clampMaxAgents("abc"))
        assertEquals(3, RedTeamSettings.DEFAULT_MAX_AGENTS)
        assertEquals(10, RedTeamSettings.MAX_AGENTS_LIMIT)
        // 硬上限：再多也不会更快，同一个 API Key 的速率限制会先到。
        assertEquals(10, RedTeamSettings.clampMaxAgents(100))
        assertEquals(1, RedTeamSettings.clampMaxAgents(-5))
        assertEquals(2, RedTeamSettings.clampMaxAgents(2.7))
    }

    @Test
    fun `max agents resolution order matches upstream`() {
        rows("maxAgentsResolution").forEach { row ->
            val label = row["label"]!!.jsonPrimitive.content
            val expectedValue = row["value"]!!.jsonPrimitive.content.toInt()
            val expectedSource = row["source"]!!.jsonPrimitive.content
            val settingsValue = RedTeamSettings.maxAgentsFromJson(row["fileJson"].text())
            val envValue = when (label) {
                "env", "settings-beats-env", "zero-settings-falls-through" -> "5"
                else -> null
            }
            val actual = RedTeamSettings.maxAgentsOf(settingsValue, envValue)
            assertEquals(expectedValue, actual.value, "value@$label")
            assertEquals(expectedSource, actual.source.id, "source@$label")
        }
    }

    /** 来源要如实说明，否则用户会以为「我改了没生效」。 */
    @Test
    fun `settings beat env which beats default`() {
        assertEquals(RedTeamSettings.MaxAgentsSource.SETTINGS, RedTeamSettings.maxAgentsOf(7.0, "5").source)
        assertEquals(RedTeamSettings.MaxAgentsSource.ENV, RedTeamSettings.maxAgentsOf(null, "5").source)
        assertEquals(RedTeamSettings.MaxAgentsSource.DEFAULT, RedTeamSettings.maxAgentsOf(null, null).source)
        // 填 0 视为未设置，让位给环境变量 —— 而不是被解释成「只允许 1 个」。
        assertEquals(5, RedTeamSettings.maxAgentsOf(0.0, "5").value)
    }

    @Test
    fun `settings json round trips and preserves other fields`() {
        val written = RedTeamSettings.withMaxAgents("""{"other":"keep","maxAgents":2}""", 6)
        assertTrue(written.contains("\"other\""), "other fields must survive: $written")
        assertEquals(6.0, RedTeamSettings.maxAgentsFromJson(written))
        // 坏 JSON 不抛，按未设置处理。
        assertNull(RedTeamSettings.maxAgentsFromJson("{not json"))
        assertNull(RedTeamSettings.maxAgentsFromJson(""))
    }
}