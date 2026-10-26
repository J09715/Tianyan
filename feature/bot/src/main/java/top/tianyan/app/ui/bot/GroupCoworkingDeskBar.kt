package top.tianyan.app.ui.bot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 群聊顶部「像素小人工位看板」（Cyber Coworking Space）。
 * 展示群内所有 Bot 的实时工位、动态像素形象与工作状态，支持点击快速 @提及。
 */
@Composable
internal fun GroupCoworkingDeskBar(
    members: List<Bot>,
    messages: List<GroupMessage>,
    assignments: List<BotAssignment>,
    onSelectBot: (Bot) -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = true,
) {
    if (members.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    val streamingBySender = remember(messages) {
        messages.filter { it.isStreaming }.associateBy { it.senderId }
    }
    val activeAssignmentByBot = remember(assignments) {
        assignments.filter { it.status.isActive }.associateBy { it.botId }
    }

    val states = remember(members, streamingBySender, activeAssignmentByBot) {
        members.associate { bot ->
            bot.id to computeMemberAvatarState(
                bot = bot,
                streamingMessage = streamingBySender[bot.id],
                assignment = activeAssignmentByBot[bot.id],
            )
        }
    }

    val busyCount = remember(states) {
        states.values.count { it != BotAvatarState.IDLE }
    }

    RuntimeCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("group-coworking-desk-bar"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            // Header summary strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Sessions,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.fbot_desk_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " · " + stringResource(R.string.fbot_desk_members_count, members.size) +
                        if (busyCount > 0) stringResource(R.string.fbot_desk_busy_count, busyCount) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (busyCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RuntimeIconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp),
                    contentDescription = stringResource(if (expanded) R.string.fbot_desk_collapse else R.string.fbot_desk_expand),
                ) {
                    RuntimeIcon(
                        name = if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Compact mode preview strip
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    members.forEach { bot ->
                        val botState = states[bot.id] ?: BotAvatarState.IDLE
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectBot(bot) }
                                .padding(2.dp),
                        ) {
                            BotAvatar(
                                seed = bot.avatarSeed,
                                colorHex = bot.colorHex,
                                size = 26.dp,
                                state = botState,
                            )
                        }
                    }
                }
            }

            // Expanded Workstations Grid / Carousel
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    members.forEach { bot ->
                        val botState = states[bot.id] ?: BotAvatarState.IDLE
                        DeskStationCard(
                            bot = bot,
                            state = botState,
                            onClick = { onSelectBot(bot) },
                        )
                    }
                }
            }
        }
    }
}

/** 单个智能体工位卡片 */
@Composable
private fun DeskStationCard(
    bot: Bot,
    state: BotAvatarState,
    onClick: () -> Unit,
) {
    val statusColor = when (state) {
        BotAvatarState.THINKING -> Color(0xFFFFB74D)
        BotAvatarState.EXECUTING -> MaterialTheme.colorScheme.primary
        BotAvatarState.ACTIVE -> MaterialTheme.colorScheme.primary
        BotAvatarState.BLOCKED -> MaterialTheme.colorScheme.error
        BotAvatarState.SUCCESS -> Color(0xFF4CAF50)
        BotAvatarState.FAILED -> MaterialTheme.colorScheme.error
        BotAvatarState.IDLE -> MaterialTheme.colorScheme.outline
    }

    val statusText = when (state) {
        BotAvatarState.THINKING -> stringResource(R.string.fbot_desk_status_thinking)
        BotAvatarState.EXECUTING -> stringResource(R.string.fbot_desk_status_executing)
        BotAvatarState.ACTIVE -> stringResource(R.string.fbot_desk_status_active)
        BotAvatarState.BLOCKED -> stringResource(R.string.fbot_desk_status_blocked)
        BotAvatarState.SUCCESS -> stringResource(R.string.fbot_desk_status_success)
        BotAvatarState.FAILED -> stringResource(R.string.fbot_desk_status_failed)
        BotAvatarState.IDLE -> stringResource(R.string.fbot_desk_status_idle)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(88.dp)
            .testTag("desk-station-${bot.id}"),
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Member Name + Chief tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (bot.isMainBot) {
                    Text(
                        text = "👑",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
                Text(
                    text = bot.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            // Animated Pixel Character at Station
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier.size(46.dp),
            ) {
                BotAvatar(
                    seed = bot.avatarSeed,
                    colorHex = bot.colorHex,
                    size = 40.dp,
                    state = state,
                )
                // Pixel desk rim under avatar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(3.dp)
                        .background(statusColor.copy(alpha = 0.6f), RoundedCornerShape(1.5.dp)),
                )
            }

            // Status Badge
            Surface(
                color = statusColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(statusColor, CircleShape),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 衍生智能体当前视觉状态 */
internal fun computeMemberAvatarState(
    bot: Bot,
    streamingMessage: GroupMessage?,
    assignment: BotAssignment?,
): BotAvatarState = when {
    assignment?.status == BotAssignmentStatus.BLOCKED -> BotAvatarState.BLOCKED
    assignment?.status == BotAssignmentStatus.FAILED -> BotAvatarState.FAILED
    assignment?.status == BotAssignmentStatus.COMPLETED -> BotAvatarState.SUCCESS
    streamingMessage != null && streamingMessage.steps.any { it.success == null } -> BotAvatarState.EXECUTING
    streamingMessage != null && streamingMessage.reasoning.isNotBlank() && streamingMessage.content.isBlank() -> BotAvatarState.THINKING
    streamingMessage != null -> BotAvatarState.ACTIVE
    assignment?.status == BotAssignmentStatus.RUNNING -> BotAvatarState.EXECUTING
    else -> BotAvatarState.IDLE
}
