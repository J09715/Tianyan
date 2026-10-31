package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Re-export of [top.tianyan.app.ui.components.MarkdownText] for backward compatibility in feature:chat.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentCacheKey: String? = null,
) {
    top.tianyan.app.ui.components.MarkdownText(
        markdown = markdown,
        modifier = modifier,
        contentCacheKey = contentCacheKey,
    )
}

val LocalSandboxHostRoots get() = top.tianyan.app.ui.components.LocalSandboxHostRoots

internal fun markdownBlocksPreview(
    markdown: String,
    contentCacheKey: String? = null,
    largeCacheMinChars: Int = 128_000,
): String = top.tianyan.app.ui.components.markdownBlocksPreview(markdown, contentCacheKey, largeCacheMinChars)

internal fun clearMarkdownCachesForTest() {
    top.tianyan.app.ui.components.clearMarkdownCachesForTest()
}
