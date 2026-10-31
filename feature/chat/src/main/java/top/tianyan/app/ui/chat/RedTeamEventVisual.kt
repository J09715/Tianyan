package top.tianyan.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 红队相关运行事件的视觉映射。
 *
 * 单独成文件而不是塞回 [eventVisual] 的大 when：那个函数所在文件已到行数棘轮上限，
 * 且红队事件与其余事件在语义上属于不同域（作战状态 vs 会话生命周期）。
 *
 * @return 非红队事件返回 null，由调用方继续走原有分支。
 */
@Composable
internal fun redTeamEventVisual(event: HarnessEvent): EventVisual? = when (event) {
    is HarnessEvent.RedTeamFactChanged -> EventVisual(
        RuntimeIconName.Shield, MaterialTheme.colorScheme.error,
        "红队事实已更新 · ${event.kind}", "${event.status} · ${event.factId}",
    )
    is HarnessEvent.RedTeamPhaseChanged -> EventVisual(
        RuntimeIconName.Play, MaterialTheme.colorScheme.error,
        "红队阶段 · ${event.phase}", event.detail ?: "阶段状态已更新",
    )
    else -> null
}

/** 运行事件一行的视觉描述；与 [eventVisual] 分离以便按域拆分文件。 */
internal data class EventVisual(
    val icon: RuntimeIconName,
    val color: androidx.compose.ui.graphics.Color,
    val title: String,
    val detail: String,
)
