package top.tianyan.app.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** 系统分享快捷指令：对预填文本的一键包装动作。 */
enum class ShareQuickAction(@StringRes val labelRes: Int) {
    SUMMARIZE(R.string.chat_share_chip_summarize),
    TRANSLATE_ZH(R.string.chat_share_chip_translate),
    SAVE_TO_SANDBOX(R.string.chat_share_chip_save),
}

/** 分享快捷指令行的状态：null 表示隐藏；onAction/onDismiss 由 ViewModel 提供。 */
data class ShareQuickActionsState(
    val text: String,
    val onAction: (ShareQuickAction) -> Unit,
    val onDismiss: () -> Unit,
)

/** 把分享文本包装成对应指令的完整 prompt（--- 围栏隔离原文，避免与指令混淆）。 */
fun wrapSharePrompt(action: ShareQuickAction, text: String): String = when (action) {
    ShareQuickAction.SUMMARIZE ->
        "请总结以下分享内容：先用三句话概括要点，再列出关键细节与结论。\n\n---\n$text\n---"
    ShareQuickAction.TRANSLATE_ZH ->
        "请将以下内容翻译成中文：保留原有格式，代码、命令与专有名词不译，术语首次出现时附原文。\n\n---\n$text\n---"
    ShareQuickAction.SAVE_TO_SANDBOX ->
        "请把以下内容原样保存到沙箱的 /workspace/shared/ 目录：文件名用内容主题的简短英文 slug 加 .md 后缀，目录不存在则先创建；保存后告知完整路径与文件大小，不要改动内容。\n\n---\n$text\n---"
}

/** 输入框上方的分享快捷指令行：预填后出现，点 chip 才发送。 */
@Composable
fun ShareQuickActionsRow(
    state: ShareQuickActionsState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RuntimeIcon(
                name = RuntimeIconName.OpenInNew,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.chat_share_received),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            ShareQuickAction.entries.forEach { action ->
                AssistChip(
                    onClick = { state.onAction(action) },
                    label = { Text(stringResource(action.labelRes), style = MaterialTheme.typography.bodySmall) },
                )
            }
            IconButton(onClick = state.onDismiss, modifier = Modifier.size(28.dp)) {
                RuntimeIcon(
                    name = RuntimeIconName.Close,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
