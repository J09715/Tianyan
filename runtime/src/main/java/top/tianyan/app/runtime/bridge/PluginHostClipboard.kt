package top.tianyan.app.runtime.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Approved plain-text writes only; no clipboard read or rich content / URI grants. */
internal object PluginHostClipboard {
    fun write(context: Context, text: String) {
        require(text.length in 1..1024 && '\u0000' !in text)
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        val clip = ClipData.newPlainText("Tianyan Web plugin", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
    }
}
