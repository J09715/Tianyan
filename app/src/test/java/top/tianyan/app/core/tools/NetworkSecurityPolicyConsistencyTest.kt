package top.tianyan.app.core.tools

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络层与业务端点策略层的一致性守卫。
 *
 * 背景：v0.18.8 把 `tianyan_network_security_config.xml` 的 base-config 从
 * `cleartextTrafficPermitted="true"` 改成 `false`，白名单只留
 * localhost/127.0.0.1/::1/10.0.2.2。而策略层 `isSafeBaseUrl()` 一直允许
 * 局域网明文端点（192.168.x.x、10.x.x.x、172.16-31.x.x）与用户自建的公网
 * http 中转。两层打架的结果是局域网模型端点被 Android 系统层拦死，
 * OkHttp 抛 "CLEARTEXT communication to X not permitted by network
 * security policy"，用户侧看到「获取不了模型列表」。
 *
 * Android NSC **不支持 CIDR/IP 段/通配符**，无法把 192.168.0.0/16 写进 XML，
 * 所以网络层只能整体放行明文，收口必须留在策略层。这条守卫固定该设计：
 * 一旦有人再把 base-config 收紧，或策略层不再放行局域网，测试立刻失败。
 */
class NetworkSecurityPolicyConsistencyTest {

    private val config = File("src/main/res/xml/tianyan_network_security_config.xml")

    @Test
    fun networkSecurityConfigExists() {
        assertTrue(
            "找不到网络安全配置：${config.absolutePath}（工作目录=${File(".").absolutePath}）",
            config.isFile,
        )
    }

    /**
     * 网络层必须放行明文。收紧它等于屏蔽所有局域网模型端点——
     * 这正是 v0.18.8 引入的功能回归。
     */
    @Test
    fun baseConfigPermitsCleartextSoLanModelEndpointsWork() {
        val xml = config.readText()
        assertTrue(
            "base-config 必须 cleartextTrafficPermitted=\"true\"：" +
                "Android NSC 无法按 IP 段放行，收紧会让 192.168.x.x / 10.x.x.x " +
                "的模型端点（llama.cpp、Ollama、LM Studio、vLLM）全部连不上。",
            Regex("""<base-config[^>]*cleartextTrafficPermitted="true"""").containsMatchIn(xml),
        )
    }

    /** 回环地址必须始终显式豁免，不受上层策略调整影响。 */
    @Test
    fun loopbackHostsAreExplicitlyExempted() {
        val xml = config.readText()
        listOf("localhost", "127.0.0.1", "::1").forEach { host ->
            assertTrue(
                "网络安全配置缺少回环豁免：$host（沙箱 HostBridge 与本地 Web 服务依赖明文回环）",
                xml.contains(">$host<"),
            )
        }
    }

    /**
     * 策略层必须放行局域网明文端点。若这条失败，说明有人收紧了
     * isSafeBaseUrl，会与网络层的放行策略再次脱节。
     */
    @Test
    fun policyLayerAcceptsLanCleartextEndpoints() {
        listOf(
            "http://192.168.1.100:11434/v1", // Ollama
            "http://192.168.1.50:8080/v1",   // llama.cpp
            "http://10.0.0.5:8000/v1",       // vLLM
            "http://172.16.0.9:1234/v1",     // LM Studio
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080",
        ).forEach { url ->
            assertTrue("局域网明文端点应被放行：$url", ProviderEndpointPolicy.isSafeBaseUrl(url))
        }
    }

    /** 含凭据的 URL 与非 http(s) 协议仍必须拒绝。 */
    @Test
    fun policyLayerStillRejectsUnsafeUrls() {
        listOf(
            "file:///data/local/tmp/api",
            "https://user:pass@example.com/v1",
            "javascript:alert(1)",
        ).forEach { url ->
            assertFalse("不安全 URL 必须拒绝：$url", ProviderEndpointPolicy.isSafeBaseUrl(url))
        }
    }

    /** 不带协议头的局域网地址应自动补 http://（而非 https），否则同样连不通。 */
    @Test
    fun lanHostsGetHttpSchemeInferred() {
        assertTrue(
            "局域网地址应自动补 http://：" +
                ProviderEndpointPolicy.normalizeUrl("192.168.1.50:11434/v1"),
            ProviderEndpointPolicy.normalizeUrl("192.168.1.50:11434/v1").startsWith("http://"),
        )
        assertTrue(
            "公网域名应自动补 https://：" +
                ProviderEndpointPolicy.normalizeUrl("api.example.com/v1"),
            ProviderEndpointPolicy.normalizeUrl("api.example.com/v1").startsWith("https://"),
        )
    }
}
