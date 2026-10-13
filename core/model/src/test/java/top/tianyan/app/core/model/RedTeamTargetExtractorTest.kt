package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 从用户消息里认目标。
 *
 * 这条逻辑的风险不对称：漏认只是让用户手动补一次，认错却会把后续所有派活、
 * 范围校验挂到一个错的目标上。所以「认不出就返回 null」是刻意的，
 * 这里钉住的也主要是「什么不该被认成目标」。
 */
class RedTeamTargetExtractorTest {

    @Test
    fun `recognises urls and keeps the full form`() {
        assertEquals(
            "https://portal.example.com/login",
            RedTeamTargetExtractor.extract("帮我看看 https://portal.example.com/login 有没有弱口令")?.target,
        )
        // 句末标点不能并进目标，否则后续请求会带上一个非法字符。
        assertEquals(
            "http://10.0.0.5:8080",
            RedTeamTargetExtractor.extract("测一下 http://10.0.0.5:8080。")?.target,
        )
    }

    @Test
    fun `recognises cidr before plain ip`() {
        val hit = RedTeamTargetExtractor.extract("目标段 10.0.0.0/24 全测一遍")
        assertEquals("10.0.0.0/24", hit?.target)
        assertEquals(RedTeamTargetExtractor.Hit.Kind.CIDR, hit?.kind)
    }

    @Test
    fun `recognises plain ipv4`() {
        val hit = RedTeamTargetExtractor.extract("这台 192.168.1.10 先打")
        assertEquals("192.168.1.10", hit?.target)
        assertEquals(RedTeamTargetExtractor.Hit.Kind.IPV4, hit?.kind)
    }

    @Test
    fun `recognises domains by known tld`() {
        assertEquals(
            "example.com",
            RedTeamTargetExtractor.extract("帮我测 example.com")?.target,
        )
        assertEquals(
            "portal.corp.internal",
            RedTeamTargetExtractor.extract("内网 portal.corp.internal 看一下")?.target,
        )
    }

    @Test
    fun `does not mistake versions filenames or prose for targets`() {
        // 版本号、文件名、路径都不是目标——它们有点号，但后缀不在白名单里。
        assertNull(RedTeamTargetExtractor.extract("升级到 v1.2.3 版本"))
        assertNull(RedTeamTargetExtractor.extract("改一下 index.js 里的逻辑"))
        assertNull(RedTeamTargetExtractor.extract("这段代码在 src/main/kotlin 下"))
        assertNull(RedTeamTargetExtractor.extract("你好"))
        assertNull(RedTeamTargetExtractor.extract(""))
        assertNull(RedTeamTargetExtractor.extract(null))
    }

    @Test
    fun `rejects out of range ipv4`() {
        // 999.1.1.1 不是合法地址，不能当目标。
        assertNull(RedTeamTargetExtractor.extract("999.1.1.1 试试"))
    }

    @Test
    fun `does not treat plain colon text as ipv6`() {
        assertNull(RedTeamTargetExtractor.extract("注意：这里要小心"))
    }

    @Test
    fun `default scope widens an ip to its segment`() {
        // 只授权单个 IP 的话，同段内的横向路径立刻全部越界，演练第一步就卡住。
        val ip = RedTeamTargetExtractor.extract("打 10.0.0.5")!!
        assertEquals("10.0.0.0/24", RedTeamTargetExtractor.defaultScope(ip))

        val cidr = RedTeamTargetExtractor.extract("打 10.0.0.0/24")!!
        assertEquals("10.0.0.0/24", RedTeamTargetExtractor.defaultScope(cidr))

        // 域名范围就是域名本身，不放大到整个网段——那是另一个授权决定。
        val domain = RedTeamTargetExtractor.extract("打 example.com")!!
        assertEquals("example.com", RedTeamTargetExtractor.defaultScope(domain))
    }

    @Test
    fun `url default scope uses the host not the full url`() {
        val url = RedTeamTargetExtractor.extract("测 https://portal.example.com/login")!!
        assertEquals("portal.example.com", RedTeamTargetExtractor.defaultScope(url))
    }
}
