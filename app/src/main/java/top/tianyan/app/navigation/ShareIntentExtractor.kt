package top.tianyan.app.navigation

import android.content.Intent

/** 从系统 ACTION_SEND Intent 中提取分享的纯文本（仅 text/plain；图片/文件分享不接）。 */
object ShareIntentExtractor {
    fun extractSharedText(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)
    }
}
