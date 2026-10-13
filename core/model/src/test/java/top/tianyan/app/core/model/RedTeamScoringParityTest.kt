package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 与上游 score-rules.js 的逐例对照测试。
 *
 * 夹具由上游实现直接跑出来（见 gen_score_fixtures.mjs），不是手写期望值——
 * 手写期望值只能证明"我实现了我以为的规则"，跑上游才能证明"我实现的是同一套规则"。
 * 评分口径一旦漂移，同一份战果会在面板、报告、攻击链算出三个不同的总分。
 */
class RedTeamScoringParityTest {

    private val fixture: JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("score_parity.json")) {
                "score_parity.json fixture missing"
            }.bufferedReader().readText(),
        ).jsonObject

    private fun arr(key: String): JsonArray = fixture[key]!!.jsonArray

    @Test
    fun `score point catalog matches upstream`() {
        val expected = arr("scorePoints")
        assertEquals(expected.size, RedTeamScoring.DEFAULT_POINTS.size, "score point count")
        expected.forEachIndexed { index, element ->
            val e = element.jsonObject
            val actual = RedTeamScoring.DEFAULT_POINTS[index]
            assertEquals(e["src"]!!.jsonPrimitive.int, actual.src, "src@$index")
            assertEquals(e["rule"]!!.jsonPrimitive.int, actual.rule, "rule@$index")
            assertEquals(e["code"]!!.jsonPrimitive.content, actual.code, "code@$index")
            assertEquals(e["points"]!!.jsonPrimitive.int, actual.points, "points@${actual.code}")
            assertEquals(e["cap"]!!.jsonPrimitive.int, actual.cap, "cap@${actual.code}")
            assertEquals(e["category"]!!.jsonPrimitive.content, actual.category, "category@${actual.code}")
            assertEquals(
                ScoreDedupScope.fromId(e["dedup_scope"]!!.jsonPrimitive.content),
                actual.dedupScope,
                "dedupScope@${actual.code}",
            )
        }
        // rule 键必须唯一：历史上用原序号当键导致一条命中吃掉另一条上限。
        assertEquals(
            RedTeamScoring.DEFAULT_POINTS.size,
            RedTeamScoring.DEFAULT_POINTS.map { it.rule }.distinct().size,
            "rule keys must be unique",
        )
    }

    @Test
    fun `target port parsing matches upstream`() {
        arr("ports").forEach { element ->
            val e = element.jsonObject
            val target = e["target"]!!.jsonPrimitive.content
            val expected = e["port"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.intOrNull
            assertEquals(expected, RedTeamScoring.parseTargetPort(target), "parseTargetPort(\"$target\")")
        }
    }

    @Test
    fun `service keys and labels match upstream`() {
        arr("serviceKeys").forEach { element ->
            val e = element.jsonObject
            val raw = e["hit"]!!.jsonObject
            val hit = ScoreHit(
                id = 1L,
                code = "probe",
                assetId = raw["asset_id"]?.jsonPrimitive?.longOrNull,
                port = raw["port"]?.jsonPrimitive?.intOrNull,
                target = raw["target"]?.jsonPrimitive?.contentOrNull,
            )
            val expectedKey = e["key"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
            assertEquals(expectedKey, RedTeamScoring.scoreServiceKey(hit), "scoreServiceKey($raw)")
            assertEquals(
                e["label"]!!.jsonPrimitive.content,
                RedTeamScoring.serviceLabel(hit),
                "serviceLabel($raw)",
            )
        }
    }

    @Test
    fun `tunnel legitimacy matches upstream`() {
        arr("tunnels").forEach { element ->
            val e = element.jsonObject
            val kind = e["entryKind"]!!.jsonPrimitive.content
            val expected = e["legit"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.booleanOrNull
            assertEquals(expected, RedTeamScoring.tunnelIsLegit(kind), "tunnelIsLegit(\"$kind\")")
        }
    }

    @Test
    fun `multipliers match upstream`() {
        arr("multipliers").forEach { element ->
            val e = element.jsonObject
            val raw = e["hit"]!!.jsonObject
            val actual = RedTeamScoring.scoreMultiplierOf(
                ipVersion = raw["ip_version"]?.jsonPrimitive?.intOrNull,
                dataScale = raw["data_scale"]?.jsonPrimitive?.contentOrNull,
                target = raw["target"]?.jsonPrimitive?.contentOrNull,
            )
            assertEquals(
                (e["multiplier"]!!.jsonPrimitive.int).toDouble(),
                actual.multiplier,
                "multiplier($raw)",
            )
            assertEquals(
                e["reasons"]!!.jsonArray.map { it.jsonPrimitive.content },
                actual.reasons,
                "reasons($raw)",
            )
        }
    }

    @Test
    fun `row count parsing matches upstream`() {
        arr("rowCounts").forEach { element ->
            val e = element.jsonObject
            val evidence = e["evidence"]!!.jsonPrimitive.content
            val expected = e["rows"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.longOrNull
            assertEquals(expected, RedTeamScoring.parseRowCount(evidence), "parseRowCount(\"$evidence\")")
            if (expected != null) {
                assertEquals(
                    e["formatted"]!!.jsonPrimitive.content,
                    RedTeamScoring.formatRows(expected),
                    "formatRows($expected)",
                )
            }
        }
    }

    @Test
    fun `score caps and dedup match upstream`() {
        arr("caps").forEach { element ->
            val e = element.jsonObject
            val name = e["name"]!!.jsonPrimitive.content
            val result = e["result"]!!.jsonObject
            val hits = scenarios.getValue(name).map { it.toScoreHit() }

            val board = RedTeamScoring.applyScoreCaps(hits, NAMES)

            assertEquals(result["capped"]!!.jsonPrimitive.int, board.cappedCount, "cappedCount@$name")

            val expectedItems = result["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expectedItems.size, board.items.size, "item count@$name")
            expectedItems.forEach { exp ->
                val id = exp["id"]!!.jsonPrimitive.longOrNull!!
                val actual = board.byId(id) ?: error("missing id $id in $name")
                assertEquals(exp["capped"]!!.jsonPrimitive.boolean, actual.capped, "capped@$name#$id")
                assertEquals(
                    exp["capped_reason_kind"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.cappedReasonKind,
                    "reason@$name#$id",
                )
                assertEquals(
                    exp["capped_by_id"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.longOrNull,
                    actual.cappedById,
                    "cappedById@$name#$id",
                )
                assertEquals(exp["cap_group"]!!.jsonPrimitive.content, actual.capGroup, "capGroup@$name#$id")
                assertEquals(
                    ScoreDedupScope.fromId(exp["dedup_scope"]!!.jsonPrimitive.content),
                    actual.dedupScope,
                    "dedupScope@$name#$id",
                )
                assertEquals(exp["rule"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.intOrNull, actual.rule, "rule@$name#$id")
                assertEquals(exp["rule_cap"]!!.jsonPrimitive.int, actual.ruleCap, "ruleCap@$name#$id")
            }

            val expectedCaps = result["caps"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expectedCaps.size, board.caps.size, "cap group count@$name")
            expectedCaps.forEach { exp ->
                val group = exp["group"]!!.jsonPrimitive.content
                val actual = board.caps[group] ?: error("missing cap group $group in $name")
                assertEquals(exp["cap"]!!.jsonPrimitive.int, actual.cap, "cap@$name/$group")
                assertEquals(exp["used"]!!.jsonPrimitive.int, actual.used, "used@$name/$group")
                assertEquals(exp["capped"]!!.jsonPrimitive.int, actual.cappedCount, "capped@$name/$group")
            }
        }
    }

    private data class Row(
        val id: Long,
        val code: String,
        val points: Int?,
        val assetId: Long?,
        val port: Int?,
        val target: String?,
        val selfCreated: Boolean,
        val systemKey: String?,
    ) {
        fun toScoreHit() = ScoreHit(
            id = id, code = code, points = points, assetId = assetId,
            port = port, target = target, selfCreated = selfCreated, systemKey = systemKey,
        )
    }

    /** 与 gen_score_fixtures.mjs 里的场景一一对应。 */
    private val scenarios: Map<String, List<Row>> = mapOf(
        "dedupSystemSameAssetPort" to listOf(
            Row(1, "server-host", 50, 1, 22, "10.0.0.5", false, null),
            Row(2, "server-host", 10, 1, 22, "10.0.0.5", false, null),
        ),
        "dedupTargetScope" to listOf(
            Row(1, "boundary-logical", 1000, 1, 0, "a.com", false, null),
            Row(2, "boundary-logical", 1000, 2, 0, "b.com", false, null),
        ),
        "dedupNoneAccumulates" to listOf(
            Row(1, "computepower-cards", 10, 1, 0, "a.com", false, null),
            Row(2, "computepower-cards", 10, 1, 0, "a.com", false, null),
            Row(3, "computepower-cards", 10, 1, 0, "a.com", false, null),
        ),
        "capAppliesPerRule" to listOf(
            Row(1, "domain-control", 400, 1, 0, "a.com", false, null),
            Row(2, "domain-control", 400, 2, 0, "b.com", false, null),
        ),
        "capOrdersByPointsDesc" to listOf(
            Row(5, "server-host", 10, 5, 1, "10.0.0.5", false, null),
            Row(6, "server-host", 600, 6, 2, "10.0.0.6", false, null),
            Row(7, "server-host", 600, 7, 3, "10.0.0.7", false, null),
        ),
        "selfCreatedExcluded" to listOf(
            Row(1, "server-host", 50, 1, 22, "10.0.0.5", true, null),
            Row(2, "server-host", 10, 1, 22, "10.0.0.5", false, null),
        ),
        "serviceScopeNoKeySkipsDedup" to listOf(
            Row(1, "netdev", 200, null, null, "", false, null),
            Row(2, "netdev", 200, null, null, "", false, null),
        ),
        "serviceScopeSameHostPort" to listOf(
            Row(1, "netdev", 200, 1, 22, "http://h:22/x", false, null),
            Row(2, "netdev", 100, 1, 22, "http://h:22/y", false, null),
        ),
        "systemKeyExplicit" to listOf(
            Row(1, "web-app", 100, 1, 443, "https://a/x", false, "t10.0.0.5:443"),
            Row(2, "web-app", 50, 2, 443, "https://b/x", false, "t10.0.0.5:443"),
        ),
        "boundarySupplyCap" to listOf(
            Row(1, "boundary-supply", 1000, 1, 0, "a.com", false, null),
            Row(2, "boundary-supply", 1000, 1, 0, "a.com:80", false, null),
            Row(3, "boundary-supply", 1000, 2, 0, "b.com", false, null),
            Row(4, "boundary-supply", 1000, 3, 0, "c.com", false, null),
        ),
        "mixedKinds" to listOf(
            Row(1, "db-credential", 50, 7, 3306, "10.0.0.7:3306", false, null),
            Row(2, "db-credential", 10, 7, 3306, "10.0.0.7:3306", false, null),
            Row(3, "bigdata-system", 1000, 7, 8080, "10.0.0.7:8080", false, null),
            Row(4, "boundary-strong", 10000, 7, 0, "10.0.0.7", false, null),
            Row(5, "computepower-cards", 10, 7, 0, "10.0.0.7", false, null),
            Row(6, "computepower-cards", 10, 7, 0, "10.0.0.7", false, null),
        ),
    )

    private companion object {
        val NAMES = mapOf(1L to "web-01", 2L to "oa-portal", 7L to "db-core")
    }
}