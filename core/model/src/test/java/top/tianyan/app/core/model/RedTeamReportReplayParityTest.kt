package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 与上游 `report-replay.js` 的逐例对照测试。
 *
 * 有一处**刻意保留的差异**，见 `http request includes the Host header upstream drops`：
 * 上游把 `Host` 头构造出来却从未拼进结果，产出的报文缺 HTTP/1.1 必填头。
 * 移植版补上该头，并在测试里把这个差异钉死，避免以后被当成"漂移"误改回去。
 */
class RedTeamReportReplayParityTest {

    private val fixture: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("replay_parity.json")) {
            "replay_parity.json fixture missing"
        }.bufferedReader().readText(),
    ).jsonObject

    private fun rows(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }

    private fun kotlinx.serialization.json.JsonElement?.str(): String? =
        this?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    @Test
    fun `target parsing matches upstream`() {
        rows("targets").forEach { row ->
            val target = row["target"]!!.jsonPrimitive.content
            val expected = row["value"]?.takeIf { it !is JsonNull }?.jsonObject
            val actual = RedTeamReportReplay.parseTarget(target)
            if (expected == null) {
                assertEquals(null, actual, "parseTarget(\"$target\") should be null")
                return@forEach
            }
            assertNotNull(actual, "parseTarget(\"$target\") should parse")
            assertEquals(expected["scheme"].str(), actual.scheme, "scheme@\"$target\"")
            assertEquals(expected["host"]!!.jsonPrimitive.content, actual.host, "host@\"$target\"")
            assertEquals(expected["port"]?.jsonPrimitive?.intOrNull, actual.port, "port@\"$target\"")
            assertEquals(expected["path"].str(), actual.path, "path@\"$target\"")
            assertEquals(expected["ipv6"]!!.jsonPrimitive.boolean, actual.ipv6, "ipv6@\"$target\"")
        }
    }

    /** IPv6 内部冒号与端口冒号混在一起，一条正则抓会错位（上游实测 host 变成 `2001:db8:`）。 */
    @Test
    fun `ipv6 authority splits host and port correctly`() {
        val t = RedTeamReportReplay.parseTarget("http://[2001:db8::1]:8080/x")
        assertNotNull(t)
        assertEquals("2001:db8::1", t.host)
        assertEquals(8080, t.port)
        assertEquals("/x", t.path)
        assertTrue(t.ipv6)

        val noPort = RedTeamReportReplay.parseTarget("https://[2001:db8::1]/y")
        assertNotNull(noPort)
        assertEquals("2001:db8::1", noPort.host)
        assertEquals(443, noPort.port)
    }

    @Test
    fun `command kind classification matches upstream`() {
        rows("kinds").forEach { row ->
            val tool = row["tool"]!!.jsonPrimitive.content
            assertEquals(row["value"].str(), RedTeamReportReplay.commandKind(tool), "commandKind(\"$tool\")")
        }
    }

    @Test
    fun `curl parsing matches upstream`() {
        rows("curls").forEach { row ->
            val tool = row["tool"]!!.jsonPrimitive.content
            val expected = row["value"]?.takeIf { it !is JsonNull }?.jsonObject
            val actual = RedTeamReportReplay.parseCurl(tool)
            if (expected == null) {
                assertEquals(null, actual, "parseCurl(\"$tool\") should be null")
                return@forEach
            }
            assertNotNull(actual, "parseCurl(\"$tool\") should parse")
            assertEquals(expected["url"]!!.jsonPrimitive.content, actual.url, "url@\"$tool\"")
            assertEquals(expected["method"]!!.jsonPrimitive.content, actual.method, "method@\"$tool\"")
            assertEquals(expected["data"].str(), actual.data, "data@\"$tool\"")
            assertEquals(expected["cookie"].str(), actual.cookie, "cookie@\"$tool\"")
            assertEquals(expected["insecure"]!!.jsonPrimitive.boolean, actual.insecure, "insecure@\"$tool\"")
            assertEquals(expected["followRedirect"]!!.jsonPrimitive.boolean, actual.followRedirect, "redirect@\"$tool\"")
            assertEquals(
                expected["headers"]!!.jsonArray.map { it.jsonPrimitive.content },
                actual.headers,
                "headers@\"$tool\"",
            )
        }
    }

    @Test
    fun `curl command building matches upstream`() {
        rows("curlCommands").forEach { row ->
            val input = row["input"]!!.jsonObject
            val actual = RedTeamReportReplay.buildCurlCommand(
                tool = input["tool"].str(),
                url = input["url"].str(),
                method = input["method"].str(),
                data = input["data"].str(),
                cookie = input["cookie"].str(),
                insecure = input["insecure"]?.jsonPrimitive?.boolean == true,
                followRedirect = input["followRedirect"]?.jsonPrimitive?.boolean == true,
            )
            assertEquals(row["value"].str(), actual, "buildCurlCommand($input)")
        }
    }

    @Test
    fun `template filling matches upstream`() {
        rows("templates").forEach { row ->
            val input = row["input"]!!.jsonObject
            val values = input["values"]!!.jsonObject
            val actual = RedTeamReportReplay.fillTemplate(
                template = input["template"].str(),
                host = values["host"].str(),
                port = values["port"].str(),
                user = values["user"].str(),
                pass = values["pass"].str(),
                url = values["url"].str(),
            )
            val expected = row["value"]!!.jsonObject
            assertEquals(expected["cmd"]!!.jsonPrimitive.content, actual.cmd, "cmd@$input")
            assertEquals(
                expected["remaining"]!!.jsonArray.map { it.jsonPrimitive.content },
                actual.remaining,
                "remaining@$input",
            )
        }
    }

    /**
     * 刻意保留的差异：上游 `buildHttpRequest` 把 `Host` 头构造出来（`lines[1]`）却从未拼进结果，
     * 于是所有合成报文都缺 HTTP/1.1 的必填头，粘进 Yakit/Burp 会因为缺 Host 而行为异常。
     * 移植版补上该头——这里断言差异存在，避免以后被当成漂移"修"回上游的 bug。
     */
    @Test
    fun `http request includes the Host header upstream drops`() {
        val upstreamWithoutHost = rows("httpRequests").mapNotNull { row ->
            row["value"].str()
        }.filter { it.contains("HTTP/1.1") }
        assertTrue(upstreamWithoutHost.isNotEmpty(), "fixture should contain synthesized requests")
        assertTrue(
            upstreamWithoutHost.none { it.contains("\r\nHost: ") },
            "fixture unexpectedly contains Host — upstream may have fixed it; re-check the divergence",
        )

        val actual = RedTeamReportReplay.buildHttpRequest(url = "http://h:8080/x")
        assertNotNull(actual)
        assertTrue(actual.text.contains("\r\nHost: h:8080\r\n"), "Host header must be present: ${actual.text}")
        assertTrue(actual.synthesized, "synthesized output must be labelled: ${actual.text}")
    }

    /** IPv6 的 Host 头必须带方括号，否则是非法头。 */
    @Test
    fun `ipv6 Host header is bracketed`() {
        val actual = RedTeamReportReplay.buildHttpRequest(url = "http://[2001:db8::1]:8080/x")
        assertNotNull(actual)
        assertTrue(actual.text.contains("Host: [2001:db8::1]:8080"), "Host must bracket the IPv6 literal: ${actual.text}")
    }

    /** 默认端口不进 Host 头。 */
    @Test
    fun `default ports are omitted from the Host header`() {
        assertEquals(true, RedTeamReportReplay.buildHttpRequest(url = "http://h/x")!!.text.contains("Host: h\r\n"))
        assertEquals(true, RedTeamReportReplay.buildHttpRequest(url = "https://h/x")!!.text.contains("Host: h\r\n"))
    }

    /** Content-Length 必须按 UTF-8 字节数，不是字符数。 */
    @Test
    fun `content length counts utf8 bytes`() {
        val body = "中文=值"
        val actual = RedTeamReportReplay.buildHttpRequest(url = "http://h/x", method = "POST", data = body)
        assertNotNull(actual)
        val expected = body.toByteArray(Charsets.UTF_8).size
        assertTrue(
            actual.text.contains("Content-Length: $expected"),
            "Content-Length must be $expected bytes (not ${body.length} chars): ${actual.text}",
        )
    }

    /** 真实 curl 命令必须原样保留，不能被重新拼装。 */
    @Test
    fun `real curl commands are preserved verbatim`() {
        val real = "curl -i -s -k -H 'X-A: 1' 'https://real.example/x'"
        assertEquals(real, RedTeamReportReplay.buildCurlCommand(tool = real, url = "http://ignored/x"))
    }
}