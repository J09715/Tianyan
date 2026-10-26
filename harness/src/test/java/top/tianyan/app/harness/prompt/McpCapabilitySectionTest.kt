package top.tianyan.app.harness.prompt

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.*
import top.tianyan.app.core.model.*
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.harness.ToolCallMode

class McpCapabilitySectionTest {
    private fun repository(enabled: Boolean, toggled: Boolean): McpServerRepository {
        val row = McpServerEntity("mcp_codegraph", "CodeGraph", "", "STDIO", "/bin/sh",
            "", "", "", isEnabled = enabled, isBuiltin = true, userToggled = toggled)
        val dao = Proxy.newProxyInstance(McpServerDao::class.java.classLoader,
            arrayOf(McpServerDao::class.java)) { _, method, _ ->
            check(method.name == "observeAll")
            flowOf(listOf(row))
        } as McpServerDao
        return McpServerRepository(dao, SecretManager())
    }

    @Test fun installedButDisabledRequestsEnablementInsteadOfReinstallation() = runBlocking {
        val text = buildMcpCapabilitySection(repository(false, true), ToolCallMode.NATIVE, setOf("mcp_codegraph"))
        val codegraph = text.lineSequence().single { it.contains("server=\"mcp_codegraph\"") }
        assertTrue(codegraph.contains("未启用"))
        assertFalse(codegraph.contains("未安装"))
        assertTrue(codegraph.contains("开启"))
    }

    @Test fun missingPackageIsNotAdvertisedAsEnabledEvenForExplicitPreference() = runBlocking {
        val text = buildMcpCapabilitySection(repository(true, true), ToolCallMode.NATIVE, emptySet())
        val codegraph = text.lineSequence().single { it.contains("server=\"mcp_codegraph\"") }
        assertTrue(codegraph.contains("未安装"))
        assertFalse(codegraph.contains("已启用"))
    }

    @Test fun repositoryRestoresUserPreferenceAndRiskMetadata() = runBlocking {
        val config = repository(false, true).servers.first().single()
        assertTrue(config.userToggled)
        assertEquals("medium", config.builtinRisk)
        assertTrue(config.requiresInstall)
    }
}
