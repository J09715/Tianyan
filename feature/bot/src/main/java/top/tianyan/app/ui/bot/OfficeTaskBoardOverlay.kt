package top.tianyan.app.ui.bot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

/** Readable, full-screen version of the pixel-whiteboard task board. */
@Composable
internal fun OfficeTaskBoardOverlay(
    tasks: List<OfficeBoardTask>,
    onClose: () -> Unit,
    onFocusOwner: (OfficeBoardTask) -> Unit,
) {
    val closeLabel = stringResource(R.string.fbot_office_kanban_close)
    val doing = tasks.count { it.column == OfficeBoardColumn.DOING }
    Box(
        Modifier
            .fillMaxSize()
            .background(OverlayBackdrop)
            .testTag("office-board-overlay"),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.fbot_office_kanban_title),
                        color = OverlayCream,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(R.string.fbot_office_kanban_summary, tasks.size, doing),
                        color = OverlayDim,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                RuntimeIconButton(onClick = onClose, contentDescription = closeLabel) {
                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(22.dp), tint = OverlayCream)
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                BoardSection(OfficeBoardColumn.TODO, tasks, onFocusOwner)
                Spacer(Modifier.size(18.dp))
                BoardSection(OfficeBoardColumn.DOING, tasks, onFocusOwner)
                Spacer(Modifier.size(18.dp))
                BoardSection(OfficeBoardColumn.DONE, tasks, onFocusOwner)
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun BoardSection(
    column: OfficeBoardColumn,
    tasks: List<OfficeBoardTask>,
    onFocusOwner: (OfficeBoardTask) -> Unit,
) {
    val accent = overlayAccent(column)
    val rows = tasks.filter { it.column == column }
    Column(Modifier.fillMaxWidth().testTag("office-board-overlay-${column.name.lowercase()}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    when (column) {
                        OfficeBoardColumn.TODO -> R.string.fbot_office_kanban_todo
                        OfficeBoardColumn.DOING -> R.string.fbot_office_kanban_doing
                        OfficeBoardColumn.DONE -> R.string.fbot_office_kanban_done
                    },
                ),
                color = accent,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = rows.size.toString(),
                color = OverlayDim,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.size(6.dp))
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.fbot_office_kanban_empty),
                color = OverlayDim,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            rows.sortedByDescending { it.elapsedSeconds }.forEach { task ->
                TaskCard(task, accent, onFocusOwner)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: OfficeBoardTask,
    accent: Color,
    onFocusOwner: (OfficeBoardTask) -> Unit,
) {
    Surface(
        onClick = { onFocusOwner(task) },
        shape = RectangleShape,
        color = OverlayCard,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("office-board-overlay-card-${task.id}"),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    color = if (task.failed) OverlayRed else OverlayCream,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${task.owner} · ${stringResource(R.string.fbot_office_kanban_elapsed, task.elapsedSeconds)}",
                        color = OverlayDim,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.failed) {
                        Text(
                            text = stringResource(R.string.fbot_office_kanban_failed),
                            color = OverlayRed,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (task.dependsOn.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.fbot_office_kanban_depends,
                            task.dependsOn.joinToString(" / ").take(44),
                        ),
                        color = OverlayDim,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = stringResource(R.string.fbot_office_kanban_locate),
                color = accent,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(18.dp), tint = accent)
        }
    }
}

private fun overlayAccent(column: OfficeBoardColumn): Color = when (column) {
    OfficeBoardColumn.TODO -> OverlayAmber
    OfficeBoardColumn.DOING -> OverlayCyan
    OfficeBoardColumn.DONE -> OverlayGreen
}

private val OverlayBackdrop = Color(0xF70A1116)
private val OverlayCard = Color(0xFF16222B)
private val OverlayCream = Color(0xFFEAF2EC)
private val OverlayDim = Color(0xFF8AA0A8)
private val OverlayAmber = Color(0xFFF3D36B)
private val OverlayCyan = Color(0xFF7EC8E3)
private val OverlayGreen = Color(0xFF8FD18A)
private val OverlayRed = Color(0xFFFFAB9A)
