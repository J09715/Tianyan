package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.BotSessionGroupRef
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.theme.semanticColors

private const val UNGROUPED_KEY = "__ungrouped__"

private data class BotSessionBucket(
    val key: String,
    val name: String?,
    val sessions: List<HarnessSessionEntity>,
    val busy: Boolean,
)

/** Bot 会话按群聊折叠：默认只看群聊维度，私有/独立会话仅在搜索时出现。 */
@Composable
internal fun BotSessionsSection(
    botSessions: List<HarnessSessionEntity>,
    groupRefs: Map<String, BotSessionGroupRef>,
    currentSessionId: String,
    sessionRunStates: Map<String, SessionRunState>,
    searchActive: Boolean,
    onSwitch: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onHide: (String, Boolean) -> Unit,
) {
    val buckets = remember(botSessions, groupRefs, sessionRunStates) {
        botSessions.groupBy { groupRefs[it.id]?.groupId }
            .map { (groupId, sessions) ->
                val ordered = sessions.sortedWith(
                    compareByDescending<HarnessSessionEntity> { sessionRunStates[it.id].isBusy() }
                        .thenByDescending { it.updatedAt },
                )
                BotSessionBucket(
                    key = groupId ?: UNGROUPED_KEY,
                    name = groupId?.let { id -> sessions.firstNotNullOfOrNull { groupRefs[it.id]?.groupName } },
                    sessions = ordered,
                    busy = ordered.any { sessionRunStates[it.id].isBusy() },
                )
            }
            .sortedWith(
                compareBy<BotSessionBucket> { it.name == null }
                    .thenByDescending { it.sessions.first().updatedAt },
            )
    }
    var expanded by rememberSaveable {
        mutableStateOf(botSessions.any { it.id == currentSessionId })
    }
    var expandedBuckets by rememberSaveable {
        mutableStateOf(
            buckets.filter { bucket -> bucket.sessions.any { it.id == currentSessionId } }
                .map { it.key }
                .toList(),
        )
    }
    // 搜索期间强制展开，保证命中的会话一眼可见。
    val isExpanded = expanded || searchActive
    val busyCount = remember(buckets) { buckets.count { it.busy } }
    LaunchedEffect(currentSessionId) {
        val activeBucket = buckets.firstOrNull { bucket -> bucket.sessions.any { it.id == currentSessionId } }
        if (activeBucket != null && activeBucket.key !in expandedBuckets) {
            expandedBuckets = expandedBuckets + activeBucket.key
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SessionSectionHeader(
            title = stringResource(R.string.chat_drawer_bot_sessions),
            count = botSessions.size,
            expanded = isExpanded,
            onToggle = { expanded = !expanded },
            alert = busyCount > 0,
        )
        if (isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                buckets.forEach { bucket ->
                    val bucketExpanded = searchActive || bucket.key in expandedBuckets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                expandedBuckets = if (bucket.key in expandedBuckets) {
                                    expandedBuckets - bucket.key
                                } else {
                                    expandedBuckets + bucket.key
                                }
                            }
                            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Chat,
                            Modifier.size(18.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = bucket.name ?: stringResource(R.string.chat_drawer_bot_sessions_other),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (bucket.busy) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(semanticColors().warning))
                        }
                        Text(
                            text = "${bucket.sessions.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        RuntimeIcon(
                            name = if (bucketExpanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bucketExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            bucket.sessions.forEach { session ->
                                SessionDrawerItem(
                                    session = session,
                                    isCurrent = session.id == currentSessionId,
                                    runState = sessionRunStates[session.id] ?: SessionRunState.IDLE,
                                    onSwitch = { onSwitch(session.id) },
                                    onRename = { onRename(session.id) },
                                    onDelete = { onDelete(session.id) },
                                    onHide = { onHide(session.id, true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SessionRunState?.isBusy(): Boolean =
    this == SessionRunState.RUNNING || this == SessionRunState.WAITING_APPROVAL || this == SessionRunState.FAILED
