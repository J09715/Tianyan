package top.tianyan.app.ui.bot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** A paper note and wooden send key sitting on the office floor. */
@Composable
internal fun PixelOfficeComposer(
    members: List<Bot>,
    value: TextFieldValue,
    attachments: List<GroupAttachment>,
    sending: Boolean,
    busy: Boolean,
    stopping: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onMention: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (GroupAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val ink = Color(0xFF3A2A1E)
    val paper = Color(0xFFF6F0E4)
    val wood = Color(PixelOfficeRoom.WOOD)
    val trim = Color(PixelOfficeRoom.TRIM)
    val mention = activeGroupMention(value)
    val candidates = if (mention == null) emptyList() else members.filter {
        it.name.contains(mention.query, ignoreCase = true) || it.role.contains(mention.query, ignoreCase = true)
    }
    val hasContent = value.text.isNotBlank() || attachments.isNotEmpty()
    val canSend = hasContent && !sending && !stopping
    val showStop = busy && !hasContent
    val attachLabel = stringResource(R.string.fbot_attach)
    val sendLabel = stringResource(R.string.fbot_send)
    val stopLabel = stringResource(R.string.fbot_stop)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (mention != null && candidates.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                candidates.forEach { bot ->
                    PixelNote(
                        onClick = { onMention(bot.name) },
                        container = paper,
                        border = trim,
                    ) {
                        Text("@${bot.name}", color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { attachment ->
                    PixelNote(onClick = { onRemoveAttachment(attachment) }, container = paper, border = trim) {
                        Text(attachment.name, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelNote(
                onClick = onAttach,
                container = wood,
                border = trim,
                contentDescription = attachLabel,
            ) {
                RuntimeIcon(RuntimeIconName.Attach, Modifier.size(18.dp), tint = paper)
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RectangleShape,
                color = paper,
                border = BorderStroke(3.dp, trim),
                shadowElevation = 0.dp,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                    cursorBrush = SolidColor(ink),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.fbot_pixel_office_say),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ink.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            val buttonEnabled = if (showStop) !stopping else canSend
            PixelNote(
                onClick = if (showStop) onStop else onSend,
                enabled = buttonEnabled,
                container = if (showStop) Color(0xFF8B3A32) else wood,
                border = trim,
                contentDescription = if (showStop) stopLabel else sendLabel,
            ) {
                when {
                    stopping || sending -> RuntimeCircularProgressIndicator(
                        Modifier.size(16.dp),
                        color = paper,
                        trackColor = paper.copy(alpha = 0.3f),
                        strokeWidth = 2.dp,
                    )
                    showStop -> RuntimeIcon(RuntimeIconName.Stop, Modifier.size(18.dp), tint = paper)
                    else -> RuntimeIcon(
                        RuntimeIconName.ArrowUp,
                        Modifier.size(18.dp),
                        tint = if (canSend) paper else paper.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PixelNote(
    onClick: () -> Unit,
    container: Color,
    border: Color,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        color = container,
        border = BorderStroke(3.dp, border),
        shadowElevation = 0.dp,
        modifier = if (contentDescription == null) {
            Modifier
        } else {
            Modifier.semantics { this.contentDescription = contentDescription }
        },
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
