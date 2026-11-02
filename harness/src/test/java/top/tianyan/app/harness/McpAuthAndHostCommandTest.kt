package top.tianyan.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.mcp.server.McpAuthFilter

/**
 * 高风险纯函数的行为测试（批次 E）：鉴权拒绝路径 + shell 参数转义。
 * 全部纯 JVM，不触网、不依赖 Android 框架。
 */
class McpAuthAndHostCommandTest {

    // ---------- McpAuthFilter：恒强制校验，拒绝一切缺省/错配 ----------

    @Test
    fun `missing header or empty token is rejected`() {
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = null, configuredToken = "tok"))
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "Bearer tok", configuredToken = ""))
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "Bearer tok", configuredToken = null))
        // 未配置 token 时整条通道关闭，即使请求带了什么 header 都不放行。
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = null, configuredToken = null))
    }

    @Test
    fun `wrong scheme wrong token and prefix attacks are rejected`() {
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "tok", configuredToken = "tok")) // 缺 Bearer 前缀
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "bearer tok", configuredToken = "tok")) // 小写 scheme
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "Bearer tok-extra", configuredToken = "tok"))
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "Bearer to", configuredToken = "tok"))
        assertTrue(!McpAuthFilter.isAcceptable(authHeader = "Bearer admin", configuredToken = "tok"))
    }

    @Test
    fun `exact match passes`() {
        assertTrue(McpAuthFilter.isAcceptable(authHeader = "Bearer s3cret", configuredToken = "s3cret"))
    }

    // ---------- HostShellCommandBuilder：参数转义与注入拒绝 ----------

    @Test
    fun `settings put quotes value with single quotes`() {
        val cmd = HostShellCommandBuilder.build(
            "settings_put",
            buildJsonObject {
                put("namespace", "system")
                put("key", "http_proxy")
                put("value", "127.0.0.1:7897")
            },
        )
        assertEquals("/system/bin/settings put system 'http_proxy' '127.0.0.1:7897'", cmd)
    }

    @Test
    fun `single quote in value is escaped not injected`() {
        val cmd = HostShellCommandBuilder.build(
            "settings_put",
            buildJsonObject {
                put("namespace", "global")
                put("key", "k")
                put("value", "'; rm -rf / #")
            },
        )
        // 恶意值必须整体落在单引号字面量里，不能以裸命令形式逃逸。
        // ShellQuote.of("'x'") = "''\\''x'\\''"（闭合-转义-重开）。
        assertEquals("/system/bin/settings put global 'k' ''\\''; rm -rf / #'", cmd)
    }

    @Test
    fun `package_list filter is grep -F quoted`() {
        val cmd = HostShellCommandBuilder.build(
            "package_list",
            buildJsonObject { put("filter", "com.android chrome") },
        )
        assertEquals("/system/bin/pm list packages | /system/bin/grep -F -- 'com.android chrome'", cmd)
    }

    @Test
    fun `missing required args throw instead of building broken command`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostShellCommandBuilder.build("settings_get", buildJsonObject { })
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostShellCommandBuilder.build(
                "settings_put",
                buildJsonObject { put("namespace", "system"); put("key", "k") },
            )
        }
    }
}
