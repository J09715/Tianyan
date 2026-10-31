package top.tianyan.app.ui.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.feature.bot.R
import kotlin.math.roundToInt

internal enum class OfficeBoardColumn { TODO, DOING, DONE }

internal data class OfficeBoardTask(
    val id: String,
    val botId: String,
    val title: String,
    val owner: String,
    val column: OfficeBoardColumn,
    val elapsedSeconds: Long,
    val failed: Boolean = false,
    val dependsOn: List<String> = emptyList(),
)

internal fun officeBoardColumn(status: BotAssignmentStatus): OfficeBoardColumn = when (status) {
    BotAssignmentStatus.QUEUED -> OfficeBoardColumn.TODO
    BotAssignmentStatus.RUNNING, BotAssignmentStatus.WAITING_CHILDREN,
    BotAssignmentStatus.BLOCKED,
    BotAssignmentStatus.INTERRUPTED,
    -> OfficeBoardColumn.DOING
    BotAssignmentStatus.COMPLETED,
    BotAssignmentStatus.FAILED,
    BotAssignmentStatus.CANCELLED,
    -> OfficeBoardColumn.DONE
}

/** To-do / doing / done board painted over the think-floor whiteboard. */
@Composable
internal fun OfficeTaskBoard(
    tasks: List<OfficeBoardTask>,
    liveNote: String?,
    originX: Float,
    originY: Float,
    scale: Float,
    board: OfficeBoardSpec = OfficeSceneSpec.Default.boards.task,
    onOpen: () -> Unit = {},
) {
    val density = LocalDensity.current
    val groups = rememberBoardGroups(tasks)
    val openLabel = stringResource(R.string.fbot_office_kanban_open)
    Box(
        Modifier
            .offset {
                IntOffset(
                    (originX + board.bounds.x * scale).roundToInt(),
                    (originY + PixelOfficeScene.y(board.bounds.y.toFloat()) * scale).roundToInt(),
                )
            }
            .size(
                roomDp(board.bounds.width.toFloat(), scale, density),
                roomDp(board.bounds.height.toFloat(), scale, density),
            )
            .taskBoardBackground(board.bounds.width)
            .testTag("office-task-board")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = openLabel,
                onClick = onOpen,
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(roomDp(1.2f, scale, density))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.fbot_office_kanban_title),
                    color = BoardCream,
                    fontSize = roomSp(3.1f, scale, density),
                    lineHeight = roomSp(3.4f, scale, density),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Spacer(Modifier.width(roomDp(1f, scale, density)))
                Box(Modifier.size(roomDp(1.1f, scale, density)).drawBehind {
                    drawRect(if (groups[OfficeBoardColumn.DOING].isNullOrEmpty()) BoardDim else BoardCyan)
                })
                Spacer(Modifier.weight(1f))
                Text(
                    text = groups.values.sumOf { it.size }.toString(),
                    color = BoardDim,
                    fontSize = roomSp(2.4f, scale, density),
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!liveNote.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.fbot_office_board_thought, liveNote.take(26)),
                    color = BoardCyan.copy(alpha = 0.9f),
                    fontSize = roomSp(2.2f, scale, density),
                    lineHeight = roomSp(2.4f, scale, density),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("office-task-board-thought"),
                )
                Spacer(Modifier.height(roomDp(0.35f, scale, density)))
            }
            Spacer(Modifier.height(roomDp(0.8f, scale, density)))
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(roomDp(1.2f, scale, density)),
            ) {
                BoardColumnView(OfficeBoardColumn.TODO, groups[OfficeBoardColumn.TODO].orEmpty(), scale, Modifier.weight(1f))
                BoardDivider(scale)
                BoardColumnView(OfficeBoardColumn.DOING, groups[OfficeBoardColumn.DOING].orEmpty(), scale, Modifier.weight(1f))
                BoardDivider(scale)
                BoardColumnView(OfficeBoardColumn.DONE, groups[OfficeBoardColumn.DONE].orEmpty(), scale, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BoardColumnView(
    column: OfficeBoardColumn,
    tasks: List<OfficeBoardTask>,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val accent = when (column) {
        OfficeBoardColumn.TODO -> BoardAmber
        OfficeBoardColumn.DOING -> BoardCyan
        OfficeBoardColumn.DONE -> BoardGreen
    }
    Column(modifier.testTag("office-task-board-${column.name.lowercase()}")) {
        Text(
            text = stringResource(
                when (column) {
                    OfficeBoardColumn.TODO -> R.string.fbot_office_kanban_todo
                    OfficeBoardColumn.DOING -> R.string.fbot_office_kanban_doing
                    OfficeBoardColumn.DONE -> R.string.fbot_office_kanban_done
                },
            ),
            color = accent,
            fontSize = roomSp(2.5f, scale, density),
            lineHeight = roomSp(2.8f, scale, density),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.height(roomDp(0.5f, scale, density)))
        if (tasks.isEmpty()) {
            Text(
                text = "—",
                color = BoardDim,
                fontSize = roomSp(2.3f, scale, density),
                lineHeight = roomSp(2.6f, scale, density),
                fontFamily = FontFamily.Monospace,
            )
        } else {
            tasks.take(2).forEachIndexed { index, task ->
                Text(
                    text = if (column == OfficeBoardColumn.DOING) task.title else "${task.owner} · ${task.title}",
                    color = if (task.failed) BoardRed else BoardCream,
                    fontSize = roomSp(2.4f, scale, density),
                    lineHeight = roomSp(2.7f, scale, density),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("office-task-card-${task.id}"),
                )
                if (column == OfficeBoardColumn.DOING && index == 0) {
                    Text(
                        text = "${task.owner} · ${stringResource(R.string.fbot_office_kanban_elapsed, task.elapsedSeconds)}",
                        color = BoardDim,
                        fontSize = roomSp(1.9f, scale, density),
                        lineHeight = roomSp(2.2f, scale, density),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (index == 0) Spacer(Modifier.height(roomDp(0.4f, scale, density)))
            }
            if (tasks.size > 2) {
                Text(
                    text = stringResource(R.string.fbot_office_kanban_more, tasks.size - 2),
                    color = accent.copy(alpha = 0.8f),
                    fontSize = roomSp(2.2f, scale, density),
                    lineHeight = roomSp(2.5f, scale, density),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun BoardDivider(scale: Float) {
    Canvas(Modifier.width(roomDp(0.35f, scale, LocalDensity.current)).fillMaxHeight()) {
        drawRect(BoardLine)
    }
}

@Composable
private fun rememberBoardGroups(tasks: List<OfficeBoardTask>): Map<OfficeBoardColumn, List<OfficeBoardTask>> =
    androidx.compose.runtime.remember(tasks) {
        tasks.groupBy { it.column }.mapValues { (_, rows) ->
            rows.sortedByDescending { it.elapsedSeconds }
        }
    }

private fun Modifier.taskBoardBackground(width: Int): Modifier = drawBehind {
    val unit = size.width / width.toFloat()
    drawRect(Color(0xFF16222B))
    drawRect(Color(0xFF0E161C), Offset(0f, size.height - unit * 2f), Size(size.width, unit * 2f))
    drawRect(BoardCyan, Offset(0f, 0f), Size(size.width, unit * 0.65f))
    drawRect(BoardLine, Offset(0f, unit * 0.65f), Size(size.width, unit * 0.25f))
    val corner = unit * 0.8f
    for (x in listOf(unit * 1.2f, size.width - unit * 2f)) {
        for (y in listOf(unit * 1.2f, size.height - unit * 2f)) {
            drawRect(BoardScrew, Offset(x, y), Size(corner, corner))
        }
    }
}

private val BoardCream = Color(0xFFEAF2EC)
private val BoardDim = Color(0xFF8AA0A8)
private val BoardLine = Color(0xFF35515C)
private val BoardScrew = Color(0xFF6F8A94)
private val BoardAmber = Color(0xFFF3D36B)
private val BoardCyan = Color(0xFF7EC8E3)
private val BoardGreen = Color(0xFF8FD18A)
private val BoardRed = Color(0xFFFFAB9A)
