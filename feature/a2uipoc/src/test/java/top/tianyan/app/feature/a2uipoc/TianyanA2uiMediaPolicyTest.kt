package top.tianyan.app.feature.a2uipoc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TianyanA2uiMediaPolicyTest {

    @Test
    fun `http and https urls are accepted and other schemes are rejected`() {
        assertTrue(TianyanA2uiMediaPolicy.httpUrlOrNull("https://example.com/a.png")!!.startsWith("https://example.com/"))
        assertTrue(TianyanA2uiMediaPolicy.httpUrlOrNull("http://example.com/v.mp4")!!.startsWith("http://"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("javascript:alert(1)"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("file:///sdcard/a.png"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("data:text/html,hi"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("/relative/path"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("https://user:pass@example.com/a"))
        assertNull(TianyanA2uiMediaPolicy.httpUrlOrNull("https://example.com/a b"))
    }

    @Test
    fun `plural template picks one or other and ignores extra arguments`() {
        val pattern = "{count, plural, one {# item} other {# items}}"
        assertEquals(
            "1 item",
            TianyanA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, mapOf("count" to 1.0, "extra" to "x")),
        )
        assertEquals(
            "3 items",
            TianyanA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, mapOf("count" to 3)),
        )
    }

    @Test
    fun `missing plural argument fails and a literal pattern is returned`() {
        val pattern = "{count, plural, other {# items}}"
        val error = runCatching {
            TianyanA2uiMediaPolicy.formatMessage(pattern, Locale.ENGLISH, emptyMap())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("plain", TianyanA2uiMediaPolicy.formatMessage("plain", Locale.ENGLISH, emptyMap()))
    }
}
