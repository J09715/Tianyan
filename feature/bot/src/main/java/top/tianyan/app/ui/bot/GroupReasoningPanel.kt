package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

/** 思考内容从一行开始随正文增长，达到上限后固定视口并自动跟随尾部。 */
@Composable
internal fun GroupReasoningPanel(messageId: String, reasoning: String, streaming: Boolean, waiting: Boolean = false, startedAtMs: Long? = null) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(true) }
    var following by rememberSaveable(messageId) { mutableStateOf(true) }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    following = scroll.value >= scroll.maxValue - 2
                else -> Unit
            }
        }
    }
    LaunchedEffect(scroll, expanded) {
        // React to measured bounds, not token arrival: text layout can finish a frame later.
        snapshotFlow { Triple(scroll.maxValue, following, scroll.isScrollInProgress) }.collect { (maximum, follow, moving) ->
            if (!moving && !follow && scroll.value >= maximum - 2) following = true
            if (expanded && follow && maximum != Int.MAX_VALUE && !moving) {
                scroll.scrollTo(maximum)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable { expanded = !expanded }.padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeProcessIcon(RuntimeIconName.Brain, streaming)
                Text(
                    stringResource(if (waiting) R.string.fbot_waiting_response else if (streaming) R.string.fbot_thinking else R.string.fbot_reasoning),
                    style = MaterialTheme.typography.bodySmall, color = runtimeProcessColor(),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(runtimeProcessPulse(streaming)),
                )
                RuntimeProcessTimer("reasoning:$messageId", streaming, startedAtMs.takeIf { waiting })
                RuntimeIcon(if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown, Modifier.size(16.dp), runtimeProcessColor())
            }
            if (expanded && reasoning.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .padding(start = 28.dp).testTag("group-reasoning-viewport"),
                ) {
                    Text(
                        reasoning.ifBlank { stringResource(R.string.fbot_waiting_response) },
                        modifier = Modifier.fillMaxWidth().verticalScroll(scroll).testTag("group-reasoning-scroll"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!following && scroll.maxValue > 0) {
                        RuntimeTextButton(
                            onClick = { following = true },
                            modifier = Modifier.align(Alignment.BottomEnd).testTag("group-reasoning-follow"),
                        ) { Text(stringResource(R.string.fbot_follow_reasoning)) }
                    }
                }
            }
        }
    }
}
