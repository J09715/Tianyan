package top.tianyan.app.plugin.ui

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.api.PluginExtensionRef

class NativePluginPageRegistryTest {
    @Test fun entriesMustHaveUniqueIdentitiesAndDeclaredPageTargets() {
        val contribution = plugin("test.one")
        val valid = NativePluginEntry("open", "main", PluginEntryLocation.MENU) { "Open" }
        val invalidLists = listOf(
            listOf(valid, valid),
            listOf(NativePluginEntry("open", "missing", PluginEntryLocation.SETTINGS) { "Missing" }),
            listOf(NativePluginEntry("../escape", "main", PluginEntryLocation.MENU) { "Invalid" }),
        )
        invalidLists.forEach { entries ->
            assertThrows(IllegalArgumentException::class.java) {
                NativePluginPageRegistry(listOf(NativePagePlugin(contribution.descriptor, contribution.pages, entries = entries)))
            }
        }
    }
    private fun plugin(id: String, pages: List<NativePluginPage> = listOf(NativePluginPage("main") {})) =
        NativePagePlugin(
            PluginDescriptor(id, "1", extensions = listOf(PluginExtension("main", PluginExtensionPoint.PAGE))),
            pages,
        )

    @Test
    fun registrationDoesNotExecutePagesAndReferencesCannotCollideAcrossPlugins() {
        val first = NativePluginPage("main") { error("Must not render during registration") }
        val second = NativePluginPage("main") { error("Must not render during registration") }
        val registry = NativePluginPageRegistry(listOf(plugin("test.one", listOf(first)), plugin("test.two", listOf(second))))
        assertSame(first, registry.resolve(PluginExtensionRef("test.one", "main")))
        assertSame(second, registry.resolve(PluginExtensionRef("test.two", "main")))
        assertNull(registry.resolve(PluginExtensionRef("test.missing", "main")))
        assertNull(registry.resolve(PluginExtensionRef("test.one", "missing")))
    }

    @Test
    fun rejectsMissingUndeclaredAndDuplicateImplementations() {
        val mismatches = listOf(
            emptyList(),
            listOf(NativePluginPage("other") {}),
            listOf(NativePluginPage("main") {}, NativePluginPage("extra") {}),
            listOf(NativePluginPage("main") {}, NativePluginPage("main") {}),
        )
        mismatches.forEach { pages ->
            assertThrows(IllegalArgumentException::class.java) {
                NativePluginPageRegistry(listOf(plugin("test.one", pages)))
            }
        }
    }

    @Test
    fun rejectsWholeRegistrationWhenOnePluginHasMissingDependencies() {
        val invalid = plugin("test.invalid")
        assertThrows(IllegalArgumentException::class.java) {
            NativePluginPageRegistry(listOf(plugin("test.healthy"), NativePagePlugin(
                invalid.descriptor.copy(dependencies = setOf("test.missing")), invalid.pages,
            )))
        }
    }

    @Test
    fun registrySnapshotsCallerOwnedPageLists() {
        val page = NativePluginPage("main") {}
        val pages = mutableListOf(page)
        val plugins = mutableListOf(plugin("test.one", pages))
        val registry = NativePluginPageRegistry(plugins)
        pages.clear()
        plugins.clear()
        assertSame(page, registry.resolve(PluginExtensionRef("test.one", "main")))
    }

    @Test
    fun requestSnapshotsArgumentsAndPreservesHostBackActionAndPlacement() {
        val arguments = mutableMapOf("project" to "project with spaces/中文")
        var backs = 0
        val request = PluginPageRequest(arguments, embedded = true, onBack = { backs++ })
        arguments["project"] = "another project"
        assertEquals("project with spaces/中文", request.arguments["project"])
        assertTrue(request.embedded)
        assertEquals(0, backs)
        request.onBack()
        assertEquals(1, backs)
        assertThrows(UnsupportedOperationException::class.java) { (request.arguments as MutableMap<*, *>).clear() }
    }
}
