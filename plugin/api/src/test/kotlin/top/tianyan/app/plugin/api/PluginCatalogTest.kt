package top.tianyan.app.plugin.api

import org.junit.Assert.*
import org.junit.Test

class PluginCatalogTest {
    private fun plugin(id: String, dependencies: Set<String> = emptySet()) = PluginDescriptor(
        id = id, version = "1.0.0", dependencies = dependencies,
        extensions = listOf(PluginExtension("main", PluginExtensionPoint.PAGE)),
    )

    @Test
    fun dependencyOrderIsDeterministicAcrossRegistrationOrders() {
        val entries = listOf(plugin("test.ui", setOf("test.runtime")), plugin("test.runtime"), plugin("test.editor"))
        val first = PluginCatalog.create(entries)
        val second = PluginCatalog.create(entries.reversed())
        assertEquals(listOf("test.editor", "test.runtime", "test.ui"), first.plugins.map { it.id })
        assertEquals(first.plugins, second.plugins)
    }

    @Test
    fun referencesUsePluginAndExtensionTogether() {
        val catalog = PluginCatalog.create(listOf(plugin("test.one"), plugin("test.two")))
        assertNotNull(catalog.extension(PluginExtensionRef("test.one", "main")))
        assertNotNull(catalog.extension(PluginExtensionRef("test.two", "main")))
        assertNull(catalog.extension(PluginExtensionRef("test.missing", "main")))
        assertNull(catalog.extension(PluginExtensionRef("test.one", "missing")))
    }

    @Test
    fun rejectsDuplicatePluginIdsInsteadOfOverwriting() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalog.create(listOf(plugin("test.one"), plugin("test.one")))
        }
    }

    @Test
    fun rejectsDuplicateExtensionIdsEvenWhenImplementationsCouldDiffer() {
        val entry = plugin("test.one")
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalog.create(listOf(entry.copy(extensions = entry.extensions + entry.extensions)))
        }
    }

    @Test
    fun rejectsMissingDependencyAndCyclesIncludingSelfDependency() {
        val graphs = listOf(
            listOf(plugin("test.one", setOf("test.missing"))),
            listOf(plugin("test.one", setOf("test.one"))),
            listOf(plugin("test.one", setOf("test.two")), plugin("test.two", setOf("test.one"))),
        )
        graphs.forEach { graph ->
            assertThrows(IllegalArgumentException::class.java) { PluginCatalog.create(graph) }
        }
    }

    @Test
    fun rejectsCycleEvenWithAnUnrelatedHealthyPlugin() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalog.create(listOf(
                plugin("test.healthy"),
                plugin("test.one", setOf("test.two")),
                plugin("test.two", setOf("test.one")),
            ))
        }
    }

    @Test
    fun validatesHostApiAtBothRangeBoundaries() {
        val entry = plugin("test.one").copy(api = PluginApiRange(2, 3))
        assertNotNull(PluginCatalog.create(listOf(entry), 2).plugin(entry.id))
        assertNotNull(PluginCatalog.create(listOf(entry), 3).plugin(entry.id))
        listOf(0, 1, 4).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { PluginCatalog.create(listOf(entry), host) }
        }
        assertThrows(IllegalArgumentException::class.java) { PluginApiRange(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PluginApiRange(3, 2) }
    }

    @Test
    fun validatesIdentifiersAndVersionWithoutNormalizingIdentity() {
        listOf("legacy-id", "Test.one", "test../one", "test.one ").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { PluginCatalog.create(listOf(plugin(id))) }
        }
        listOf("", "../main", "Main", "main ").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                PluginCatalog.create(listOf(plugin("test.one").copy(
                    extensions = listOf(PluginExtension(id, PluginExtensionPoint.PAGE)),
                )))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalog.create(listOf(plugin("test.one").copy(version = " ")))
        }
    }

    @Test
    fun copiesInputCollectionsAndDoesNotExposeMutableCatalogState() {
        val dependencies = mutableSetOf("test.base")
        val extensions = mutableListOf(PluginExtension("main", PluginExtensionPoint.PAGE))
        val entries = mutableListOf(plugin("test.base"), PluginDescriptor("test.ui", "1", dependencies = dependencies, extensions = extensions))
        val catalog = PluginCatalog.create(entries)
        dependencies.clear()
        extensions.clear()
        entries.clear()
        assertEquals(2, catalog.plugins.size)
        assertEquals(setOf("test.base"), catalog.plugin("test.ui")!!.dependencies)
        assertNotNull(catalog.extension(PluginExtensionRef("test.ui", "main")))
        assertThrows(UnsupportedOperationException::class.java) { (catalog.plugins as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (catalog.plugin("test.ui")!!.extensions as MutableList<*>).clear()
        }
    }

    @Test
    fun emptyCatalogIsValidForMinimalHostAndRecovery() {
        assertTrue(PluginCatalog.create(emptyList()).plugins.isEmpty())
    }
}
