package top.tianyan.app.harness.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginExtensionPoint

/** 插件目录在运行时严格校验 ID；这里提前锁住 Bot 插件的描述符，避免再次出现启动崩溃。 */
class BotPluginDescriptorTest {

    @Test
    fun `bot descriptors pass catalog validation`() {
        val catalog = PluginCatalog.create(
            listOf(botManagementDescriptor(), botMemoryDescriptor(), botScheduleDescriptor()),
        )
        assertEquals(3, catalog.plugins.size)
    }

    @Test
    fun `extension ids stay unique and use the catalog charset`() {
        val allowed = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
        listOf(botManagementDescriptor(), botMemoryDescriptor(), botScheduleDescriptor()).forEach { descriptor ->
            val ids = descriptor.extensions.map { it.id }
            assertEquals(ids.size, ids.distinct().size)
            ids.forEach { id ->
                assertEquals("非法扩展 ID: ${descriptor.id}/$id", true, allowed.matches(id))
            }
        }
    }

    @Test
    fun `memory context is the only non tool contribution`() {
        val descriptor = botMemoryDescriptor()
        assertEquals(
            listOf("memory-context"),
            descriptor.extensions.filter { it.point != PluginExtensionPoint.TOOL }.map { it.id },
        )
    }
}
