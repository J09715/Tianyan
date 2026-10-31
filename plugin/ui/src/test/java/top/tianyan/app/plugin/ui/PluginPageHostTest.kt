package top.tianyan.app.plugin.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.api.PluginExtension
import top.tianyan.app.plugin.api.PluginExtensionPoint
import top.tianyan.app.plugin.api.PluginExtensionRef

/** Exercises the actual composable dispatcher with an Android stub, without PTY or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PluginPageHostTest {
    @Test
    fun rendersRegisteredPageWithArgumentsBackActionAndEnclosingCompositionLocals() = runTest {
        val owner = Any()
        val localOwner = staticCompositionLocalOf<Any?> { null }
        var observedOwner: Any? = null
        var observedRequest: PluginPageRequest? = null
        var backs = 0
        val page = NativePluginPage("main") { request ->
            val currentOwner = localOwner.current
            SideEffect {
                observedOwner = currentOwner
                observedRequest = request
            }
        }
        val pages = NativePluginPageRegistry(listOf(NativePagePlugin(
            PluginDescriptor("test.page", "1", extensions = listOf(PluginExtension("main", PluginExtensionPoint.PAGE))),
            listOf(page),
        )))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(localOwner provides owner) {
                    PluginPageHost(
                        pages, PluginExtensionRef("test.page", "main"),
                        arguments = mapOf("project" to "项目/a b"), embedded = true, onBack = { backs++ },
                    )
                }
            }
            assertSame(owner, observedOwner)
            val rendered = requireNotNull(observedRequest)
            assertEquals("项目/a b", rendered.arguments["project"])
            assertTrue(rendered.embedded)
            assertEquals(0, backs)
            rendered.onBack()
            assertEquals(1, backs)
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
        override fun remove(index: Int, count: Int) = error("Unexpected UI node")
        override fun onClear() = Unit
    }
}
