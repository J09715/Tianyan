package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** 与上游 ip-utils.js 的逐例对照测试，夹具由上游实现直接产出。 */
class RedTeamIpUtilsParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("ip_parity.json")) {
            "ip_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    private fun rows(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `ipv4 integer conversion matches upstream`() {
        rows("ipToInt").forEach { row ->
            val ip = row["ip"]!!.jsonPrimitive.content
            val expected = row["value"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
            assertEquals(expected, RedTeamIpUtils.ipToInt(ip), "ipToInt(\"$ip\")")
        }
    }

    @Test
    fun `ipv6 detection matches upstream`() {
        rows("isIpv6").forEach { row ->
            val ip = row["ip"]!!.jsonPrimitive.content
            assertEquals(row["value"]!!.jsonPrimitive.boolean, RedTeamIpUtils.isIpv6(ip), "isIpv6(\"$ip\")")
        }
    }

    @Test
    fun `ipv6 expansion matches upstream`() {
        rows("expandIpv6").forEach { row ->
            val ip = row["ip"]!!.jsonPrimitive.content
            assertEquals(
                row["value"]!!.jsonArray.map { it.jsonPrimitive.content },
                RedTeamIpUtils.expandIpv6(ip),
                "expandIpv6(\"$ip\")",
            )
        }
    }

    @Test
    fun `cidr derivation matches upstream`() {
        rows("cidrOf").forEach { row ->
            val ip = row["ip"]!!.jsonPrimitive.content
            assertEquals(row["value"]!!.jsonPrimitive.content, RedTeamIpUtils.cidrOf(ip), "cidrOf(\"$ip\")")
        }
    }

    @Test
    fun `internal or external scope matches upstream`() {
        rows("scopeOfIp").forEach { row ->
            val ip = row["ip"]!!.jsonPrimitive.content
            assertEquals(row["value"]!!.jsonPrimitive.content, RedTeamIpUtils.scopeOfIp(ip), "scopeOfIp(\"$ip\")")
        }
    }

    @Test
    fun `slug helpers match upstream`() {
        rows("slugify").forEach { row ->
            // 上游 fallback 用当前时间，这里给一个固定值，只比对归一化结果部分。
            val name = row["name"]!!.jsonPrimitive.content
            val expected = row["value"]!!.jsonPrimitive.content
            if (expected.startsWith("engagement-")) return@forEach
            assertEquals(expected, RedTeamIpUtils.slugify(name, fallback = "engagement-x"), "slugify(\"$name\")")
        }
        rows("slugTarget").forEach { row ->
            val target = row["target"]!!.jsonPrimitive.content
            assertEquals(row["value"]!!.jsonPrimitive.content, RedTeamIpUtils.slugTarget(target), "slugTarget(\"$target\")")
        }
        rows("slugPoc").forEach { row ->
            val title = row["title"]!!.jsonPrimitive.content
            val cve = row["cve"]!!.jsonPrimitive.content
            val expected = row["value"]!!.jsonPrimitive.content
            if (expected.startsWith("poc-")) return@forEach
            assertEquals(expected, RedTeamIpUtils.slugPoc(title, cve, fallback = "poc-x"), "slugPoc(\"$title\", \"$cve\")")
        }
        rows("pocFilenames").forEach { row ->
            assertEquals(
                row["value"]!!.jsonPrimitive.content,
                RedTeamIpUtils.defaultPocFilename(row["kind"]!!.jsonPrimitive.content, row["language"]!!.jsonPrimitive.content),
                "defaultPocFilename(${row["kind"]}, ${row["language"]})",
            )
        }
    }

    /**
     * IPv6 归段必须走 /64，不能被一律 split('.') 拼成 `2001:db8::1.0/24`：
     * 面板左侧会因此多出一个假网段，资产归属也错。
     */
    @Test
    fun `ipv6 assets never produce a dotted cidr`() {
        // 展开后取前四组：db8 必须保留，不能被 :: 缩写吃掉。
        assertEquals("2001:db8:0:0::/64", RedTeamIpUtils.cidrOf("2001:db8::1"))
        assertEquals("fe80:0:0:0::/64", RedTeamIpUtils.cidrOf("fe80::1"))
        listOf("2001:db8::1", "fe80::1", "::ffff:10.0.0.5").forEach { ip ->
            val cidr = RedTeamIpUtils.cidrOf(ip)
            assertEquals(false, cidr.contains(".0/24"), "IPv6 \"$ip\" produced a dotted CIDR: $cidr")
        }
    }

    @Test
    fun `unrecognised input is returned verbatim`() {
        assertEquals("not-an-ip", RedTeamIpUtils.cidrOf("not-an-ip"))
        assertEquals("", RedTeamIpUtils.cidrOf(""))
        // IPv6 走不了 IPv4 整数化这条路，返回 null 让排序退回按文本比。
        assertEquals(null, RedTeamIpUtils.ipToInt("2001:db8::1"))
        assertEquals(null, RedTeamIpUtils.ipToInt("not-an-ip"))
        // 上游正则不校验八位组范围，999.1.1.1 照算 —— 刻意保持行为一致。
        assertEquals(3875602689L, RedTeamIpUtils.ipToInt("999.1.1.1"))
    }
}