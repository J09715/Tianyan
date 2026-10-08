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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与上游 `store-core.js` 阶段逻辑的逐例对照测试。
 *
 * 演练方是按「信息收集 → 互联网权限 → 边界突破 → 内网权限 → 靶标权限」这条推进线读报告的。
 * 一条「内网拿到域管」的得分如果落进「互联网资产权限」桶，整份报告的叙事就废了——
 * 而这种错位不会报错，只会让两边对不上账。
 */
class RedTeamStageParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("stage_parity.json")) {
            "stage_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    private fun rows(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        this?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    /** 阶段元数据是报告分桶与推进叙事的基准，字段必须逐一对上。 */
    @Test
    fun `stage catalog matches upstream`() {
        val upstream = rows("stages")
        assertEquals(upstream.size, RedTeamStage.DEFAULT_STAGES.size)
        upstream.forEachIndexed { index, row ->
            val stage = RedTeamStage.DEFAULT_STAGES[index]
            assertEquals(row["code"]!!.jsonPrimitive.content, stage.code)
            assertEquals(row["name"]!!.jsonPrimitive.content, stage.name)
            assertEquals(row["subtitle"]!!.jsonPrimitive.content, stage.subtitle)
            assertEquals(row["color"]!!.jsonPrimitive.content, stage.color)
            assertEquals(row["scored"]!!.jsonPrimitive.content.toInt(), stage.scored)
            assertEquals(row["goal"]!!.jsonPrimitive.content, stage.goal)
            assertEquals(row["transition"]!!.jsonPrimitive.content, stage.transition)
            assertEquals(row["tools"]!!.jsonPrimitive.content, stage.tools)
            assertEquals(
                row["sectionLabels"]!!.jsonArray.map { it.jsonPrimitive.content },
                stage.sections.map { it.label },
            )
            row["sectionItems"]!!.jsonArray.forEachIndexed { i, items ->
                assertEquals(
                    items.jsonArray.map { it.jsonPrimitive.content },
                    stage.sections[i].items,
                    "section $i items of ${stage.code}",
                )
            }
        }
    }

    @Test
    fun `only information gathering is unscored`() {
        // 前置阶段不计分，其余四个是拿分阶段。
        assertEquals(0, RedTeamStage.DEFAULT_STAGES.first { it.code == "recon" }.scored)
        assertEquals(1, RedTeamStage.DEFAULT_STAGES.first { it.code == "target" }.scored)
        assertEquals(4, RedTeamStage.DEFAULT_STAGES.count { it.scored == 1 })
        assertEquals(RedTeamStage.VALID_STAGE_CODES, RedTeamStage.DEFAULT_STAGES.map { it.code })
    }

    /** 四级优先级：显式 stage_code > 类型特判 > 资产内外网 > target 地址。 */
    @Test
    fun `score stage derivation matches upstream`() {
        rows("stageOf").forEach { row ->
            val hit = row["hit"]!!.jsonObject
            val actual = RedTeamStage.scoreStageOf(
                stageCode = hit["stage_code"].text(),
                hitCode = hit["code"].text(),
                assetScope = row["scope"].text(),
                target = hit["target"].text(),
            )
            assertEquals(row["value"]!!.jsonPrimitive.content, actual, "scoreStageOf($hit, ${row["scope"]})")
        }
    }

    @Test
    fun `explicit stage wins over type override`() {
        // 集权系统本应归靶标，但显式指定 internal 时以显式为准。
        assertEquals("internal", RedTeamStage.scoreStageOf("internal", "mail-admin", "external", "10.0.0.5"))
        // 显式为空串则继续往下走。
        assertEquals("internal", RedTeamStage.scoreStageOf("", null, "internal", null))
    }

    @Test
    fun `engagement target systems always land in the target stage`() {
        // 规则 5/6/7（邮箱 / 办公业务 / 集权系统）就是演练的靶标，
        // 哪怕资产登记成 external 也必须落在靶标桶，否则报告会把靶标得分算进互联网侧。
        listOf("mail-admin", "mail-user", "biz-admin", "biz-user", "central-admin", "central-user", "central-managed", "web-app", "central-system", "core-system")
            .forEach { code ->
                assertEquals("target", RedTeamStage.scoreStageOf(null, code, "external", "203.0.113.5"), "code=$code")
            }
        listOf("boundary", "boundary-logical", "boundary-strong", "boundary-physical", "boundary-supply")
            .forEach { code ->
                assertEquals("boundary", RedTeamStage.scoreStageOf(null, code, "internal", "10.0.0.5"), "code=$code")
            }
    }

    @Test
    fun `scope and address are the fallback signals`() {
        assertEquals("internal", RedTeamStage.scoreStageOf(null, "weak-password", "internal", null))
        assertEquals("internet", RedTeamStage.scoreStageOf(null, "weak-password", "external", null))
        // target 里任意位置出现私有 IP → 内网。
        assertEquals("internal", RedTeamStage.scoreStageOf(null, null, null, "find at http://10.1.2.3:8080/x"))
        assertEquals("internet", RedTeamStage.scoreStageOf(null, null, null, "find at http://203.0.113.9/x"))
        // 域名没有 IP 可判 → 归互联网侧。
        assertEquals("internet", RedTeamStage.scoreStageOf(null, null, null, "example.com"))
    }

    @Test
    fun `chain stage resolution matches upstream`() {
        rows("chain").forEach { row ->
            val actual = RedTeamStage.resolveChainStage(row["stage_code"].text(), row["stage"]!!.jsonPrimitive.content)
            assertEquals(row["code"]!!.jsonPrimitive.content, actual.code, "code for $row")
            assertEquals(row["warning"].text(), actual.warning, "warning for $row")
        }
    }

    /**
     * 非法阶段码不能静默丢桶：外部工具爱写 external / foothold / tunnel / privilege，
     * 这些值分不了桶，步骤会落在任何阶段之外——所以退回老 stage 并**明确告警**。
     */
    @Test
    fun `invalid chain stage falls back with a warning`() {
        val result = RedTeamStage.resolveChainStage("external", "access")
        assertEquals("internal", result.code, "legacy access maps to internal")
        assertNotNull(result.warning)
        assertTrue(result.warning.contains("external"), "warning must quote the bad value: ${result.warning}")
        assertTrue(result.warning.contains("internal"), "warning must say where it landed: ${result.warning}")

        assertNull(RedTeamStage.resolveChainStage("target", "pivot").warning, "valid code needs no warning")
        // 未提供 stage_code 时按老 stage 兜底，同样不告警。
        assertNull(RedTeamStage.resolveChainStage(null, "data").warning)
    }

    @Test
    fun `legacy stage mapping covers every historical stage`() {
        assertEquals("internet", RedTeamStage.resolveChainStage(null, "vuln").code)
        assertEquals("internet", RedTeamStage.resolveChainStage(null, "exploit").code)
        assertEquals("internal", RedTeamStage.resolveChainStage(null, "access").code)
        assertEquals("boundary", RedTeamStage.resolveChainStage(null, "pivot").code)
        assertEquals("internal", RedTeamStage.resolveChainStage(null, "data").code)
        // 未知的 stage 兜到信息收集，而不是丢掉这步。
        assertEquals("recon", RedTeamStage.resolveChainStage(null, "totally-unknown").code)
    }

    /** 同一个目标的写法不同必须落到同一个键上，否则复现证据会散成多组。 */
    @Test
    fun `target key normalization matches upstream`() {
        rows("targetKey").forEach { row ->
            assertEquals(
                row["value"]!!.jsonPrimitive.content,
                RedTeamStage.targetKey(row["target"].text(), row["fallbackIp"].text()),
                "targetKey(${row["target"]}, ${row["fallbackIp"]})",
            )
        }
    }

    @Test
    fun `target key groups equivalent spellings together`() {
        val withPath = RedTeamStage.targetKey("http://a.com/x", null)
        val withQuery = RedTeamStage.targetKey("http://a.com/y?z=1", null)
        assertEquals(withPath, withQuery, "same host, different path must share a key")
        assertEquals("http://a.com", withPath)
        // 端口属于目标标识的一部分，不能被截掉。
        assertEquals("10.0.0.5:8080", RedTeamStage.targetKey("10.0.0.5:8080", null))
        // 目标为空时回落到已知 IP，再空才给占位。
        assertEquals("10.0.0.5", RedTeamStage.targetKey("", "10.0.0.5"))
        assertEquals("(未指定目标)", RedTeamStage.targetKey("", null))
    }
}