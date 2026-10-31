package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.model.protocol.A2uiException
import androidx.a2ui.model.protocol.A2uiExecutionContext
import java.lang.reflect.Proxy
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TianyanA2uiClientFunctionsTest {
    private val context = Proxy.newProxyInstance(
        A2uiExecutionContext::class.java.classLoader, arrayOf(A2uiExecutionContext::class.java),
    ) { _, _, _ -> error("These client functions must not access the surface data model") } as A2uiExecutionContext

    @Test fun newFunctionsInterfaceRetainsUrlSchemeRestrictions() {
        listOf("javascript:alert(1)", "file:///sdcard/test", "intent://example", "https://user:pass@example.com").forEach { url ->
            val failure = assertThrows(A2uiException.A2uiRuntimeException::class.java) {
                TianyanA2uiClientFunctions.openUrl(url, context)
            }
            assertTrue(failure.message.orEmpty().contains("仅允许"))
        }
    }

    @Test fun pluralizationThroughNewInterfacePreservesHostFormatting() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("1 item", TianyanA2uiClientFunctions.pluralize(1.0, null, "# item", null, null, null, "# items", context))
            assertEquals("3 items", TianyanA2uiClientFunctions.pluralize(3.0, null, "# item", null, null, null, "# items", context))
            assertEquals("3 个项目", TianyanA2uiClientFunctions.pluralize(3.0, null, null, null, null, null, "# 个项目", context))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun defaultValidationFunctionsRemainAvailable() {
        assertFalse(TianyanA2uiClientFunctions.required(null, context))
        assertTrue(TianyanA2uiClientFunctions.required("value", context))
        assertTrue(TianyanA2uiClientFunctions.email("user@example.com", context))
    }
}
