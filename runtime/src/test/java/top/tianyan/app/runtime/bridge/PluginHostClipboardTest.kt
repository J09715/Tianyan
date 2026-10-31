package top.tianyan.app.runtime.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginHostClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun writesOnlyPlainTextWithSensitivePreviewFlag() {
        clipboard.setPrimaryClip(ClipData.newPlainText("old", "previous"))
        PluginHostClipboard.write(context, "天衍\nhttps://example.invalid")
        val clip = clipboard.primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals("天衍\nhttps://example.invalid", clip.getItemAt(0).text.toString())
        assertNull(clip.getItemAt(0).uri)
        assertNull(clip.getItemAt(0).intent)
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test fun invalidTextLeavesExistingClipboardUntouched() {
        clipboard.setPrimaryClip(ClipData.newPlainText("old", "previous"))
        listOf("", "a\u0000b", "a".repeat(1025)).forEach {
            assertThrows(IllegalArgumentException::class.java) { PluginHostClipboard.write(context, it) }
            assertEquals("previous", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
