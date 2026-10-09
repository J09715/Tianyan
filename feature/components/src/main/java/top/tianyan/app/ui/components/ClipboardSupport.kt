package top.tianyan.app.ui.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 复制纯文本到剪贴板。
 *
 * 为什么需要这层封装：`LocalClipboardManager` 已废弃（官方提示改用 `LocalClipboard`），
 * 但新 API 是挂起的 `setClipEntry(ClipEntry, Continuation)`，直接在点击回调里调不了——
 * 每处都得自己起协程作用域、把字符串包成 `ClipData` → `ClipEntry`。
 * 散落写三遍必然漏掉某处的异常处理，所以收口成一个可组合函数。
 *
 * 返回的 lambda 是即发即忘的：复制失败不该打断用户正在做的事
 * （长按一个文件路径复制，失败时弹崩溃比没复制更糟），因此内部吞掉异常。
 */
@Composable
fun rememberTextCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope: CoroutineScope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String ->
            scope.launch {
                runCatching {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(CLIP_LABEL, text)),
                    )
                }
            }
        }
    }
}

/** 剪贴板条目的展示标签；部分输入法/系统面板会读它。 */
private const val CLIP_LABEL = "tianyan"
