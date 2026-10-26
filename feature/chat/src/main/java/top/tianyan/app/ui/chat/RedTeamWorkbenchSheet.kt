package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 红队工作台整页弹层。
 *
 * 与工具链 / 分支面板同构：点工作台控制的入口整页进入，而不是作为卡片叠在对话上
 * （叠卡片会让对话正文、快捷开始、输入框从半透明卡片下透出来）。
 *
 * 单独成文件：ChatScreen 已接近文件行数棘轮上限，而这块内容与主屏装配无耦合。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RedTeamWorkbenchSheet(
    session: HarnessSessionEntity,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val facts by viewModel.redTeamFacts.collectAsStateWithLifecycle(initialValue = emptyList())
    PlainBackdropModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("红队工作台", style = MaterialTheme.typography.titleLarge)
                RuntimeIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                    contentDescription = "关闭",
                ) { RuntimeIcon(RuntimeIconName.Close, Modifier.size(22.dp)) }
            }
            RedTeamPanel(
                session = session,
                facts = facts,
                onBindTarget = { target, scope -> viewModel.bindRedTeamTarget(target, scope) },
            )
        }
    }
}
