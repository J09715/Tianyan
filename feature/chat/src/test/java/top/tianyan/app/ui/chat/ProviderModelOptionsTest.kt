package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelOptionsTest {
    @Test
    fun `switched model outside saved profile remains visible as an option`() {
        assertEquals(listOf("model-a", "model-b"), providerModelOptions("model-a", "model-b"))
    }

    @Test
    fun `saved selection is not duplicated`() {
        assertEquals(listOf("model-a", "model-b"), providerModelOptions(" model-a,model-b ", "model-b"))
    }

    @Test
    fun `inactive provider retains its own saved options`() {
        assertEquals(listOf("model-a"), providerModelOptions("model-a", null))
    }

    @Test
    fun `manual model is visible with an empty saved list`() {
        assertEquals(listOf("manual-model"), providerModelOptions(" , ", " manual-model "))
    }
}
