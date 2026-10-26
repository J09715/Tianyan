package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePayloadCompressorTest {

    @Test
    fun `non image urls are left untouched`() {
        assertEquals("https://example.invalid/a.png", ImagePayloadCompressor.downscaleDataUrl("https://example.invalid/a.png"))
        assertEquals("not-a-data-url", ImagePayloadCompressor.downscaleDataUrl("not-a-data-url"))
    }

    @Test
    fun `small data urls skip the decoder`() {
        val small = "data:image/jpeg;base64," + "A".repeat(120)
        assertSame(small, ImagePayloadCompressor.downscaleDataUrl(small))
    }

    @Test
    fun `messages without images keep identity`() {
        val messages = listOf(ApiMessage(role = "user", content = "hi"))
        assertSame(messages, ImagePayloadCompressor.downscale(messages))
        val harness = listOf(UserMessage("u1", 1, "hi"))
        assertSame(harness, ImagePayloadCompressor.downscaleHarness(harness))
    }

    @Test
    fun `oversized fake payload stays within runCatching fallback`() {
        val huge = "data:image/png;base64," + "A".repeat(ImagePayloadCompressor.SKIP_UNDER_BYTES * 2)
        val out = ImagePayloadCompressor.downscaleDataUrl(huge)
        assertTrue(out.startsWith("data:image/"))
    }
}
