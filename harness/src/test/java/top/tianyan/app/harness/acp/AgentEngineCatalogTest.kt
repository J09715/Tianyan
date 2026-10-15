package top.tianyan.app.harness.acp

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录校验规则的纯 JUnit 断言（不依赖 Robolectric/Android 资源）。
 *
 * 随包分发的 engines.json 单独用一个文件级测试守护：它一旦被改坏，
 * 选择器会静默变空，而单测里的合成 JSON 仍全绿——那是会骗过 CI 的盲区。
 */
class AgentEngineCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(source: String) = AgentEngineCatalogParser.parse(source, json)

    @Test
    fun duplicateEngineIdsAreRejected() {
        val duplicated = """
            {"schemaVersion":1,"engines":[
              {"id":"a","name":"A","toolId":"a","command":"a"},
              {"id":"a","name":"B","toolId":"b","command":"b"}
            ]}
        """.trimIndent()
        assertTrue(runCatching { parse(duplicated) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun retiredEnginesAreFilteredOut() {
        val source = """
            {"schemaVersion":1,"retiredEngineIds":["old"],"engines":[
              {"id":"keep","name":"Keep","toolId":"keep","command":"keep"},
              {"id":"old","name":"Old","toolId":"old","command":"old"}
            ]}
        """.trimIndent()
        assertEquals(listOf("keep"), parse(source).map { it.id })
    }

    @Test
    fun engineWithoutCommandIsRejected() {
        val source = """{"schemaVersion":1,"engines":[{"id":"x","name":"X","toolId":"x","command":""}]}"""
        assertTrue(runCatching { parse(source) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun engineWithoutToolIdIsRejected() {
        // toolId 是「选择器列出」与「插件中心可安装」之间唯一的连接键，缺失必须立刻失败。
        val source = """{"schemaVersion":1,"engines":[{"id":"x","name":"X","toolId":"","command":"x"}]}"""
        assertTrue(runCatching { parse(source) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        assertTrue(
            runCatching { parse("""{"schemaVersion":99,"engines":[]}""") }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun capabilitiesArePreservedAsRawJson() {
        val source = """
            {"schemaVersion":1,"engines":[{"id":"x","name":"X","toolId":"x","command":"x",
             "declaredCapabilities":{"tools":{"shell":true}}}]}
        """.trimIndent()
        val engine = parse(source).single()
        assertTrue(engine.declaredCapabilities.containsKey("tools"))
    }

    @Test
    fun catalogDistinguishesEngineIdsFromPersonaIds() {
        val engines = loadBundledCatalogAsset()

        assertTrue(AgentEngineIndex.isEngine(engines, "codex"))
        assertTrue(AgentEngineIndex.isEngine(engines, "claude-code"))
        // 天衍本体的人格档案 id 不应被误判为引擎，否则会把人格当进程去拉起。
        assertFalse(AgentEngineIndex.isEngine(engines, "agency_engineering_frontend_developer"))
        assertFalse(AgentEngineIndex.isEngine(engines, ""))
        assertFalse(AgentEngineIndex.isEngine(engines, null))
        assertNull(AgentEngineIndex.find(engines, "agency_engineering_frontend_developer"))
    }

    // ---- 随包资源守护 ----

    @Test
    fun bundledCatalogDeclaresEverySupportedEngine() {
        val engines = loadBundledCatalogAsset()
        val ids = engines.map { it.id }.toSet()

        assertEquals(
            setOf("claude-code", "codex", "deepseek-harness", "opencode", "kimi-code"),
            ids,
        )
        engines.forEach { engine ->
            assertTrue("blank name: ${engine.id}", engine.name.isNotBlank())
            assertTrue("blank command: ${engine.id}", engine.command.isNotBlank())
            assertTrue("blank toolId: ${engine.id}", engine.toolId.isNotBlank())
            assertTrue("blank description: ${engine.id}", engine.description.isNotBlank())
        }
    }

    @Test
    fun bundledEngineToolIdsAllExistInTheInstallRegistry() {
        val engineToolIds = loadBundledCatalogAsset().map { it.toolId }.toSet()
        val registryPath = listOf(
            File("../app/src/main/assets/registry/tools.json"),
            File("app/src/main/assets/registry/tools.json"),
        ).firstOrNull { it.isFile } ?: error("找不到插件注册表，无法校验 toolId 对齐")
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(registryPath.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "引擎 toolId 在插件注册表里不存在：${engineToolIds - ids}",
            ids.containsAll(engineToolIds),
        )
    }

    private fun loadBundledCatalogAsset(): List<AgentEngineProfile> {
        val asset = listOf(
            File("src/main/assets/agent_engines/engines.json"),
            File("harness/src/main/assets/agent_engines/engines.json"),
        ).firstOrNull { it.isFile } ?: error("找不到 engines.json")
        return parse(asset.readText())
    }
}
