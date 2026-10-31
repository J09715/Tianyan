package top.tianyan.app.ui.settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.settings.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
@Composable
internal fun TerminalPreviewCard(
    colorScheme: String,
    fontSizeSp: Int,
) {
    val (bg, textColor, accentColor) = when (colorScheme) {
        "matrix" -> Triple(Color(0xFF0A0F0D), Color(0xFF10B981), Color(0xFF34D399))
        "amber" -> Triple(Color(0xFF140F0A), Color(0xFFF59E0B), Color(0xFFFBBF24))
        "aurora" -> Triple(Color(0xFF0D1424), Color(0xFF38BDF8), Color(0xFF818CF8))
        else -> Triple(Color(0xFF0F1117), Color(0xFFE2E2E9), Color(0xFF60A5FA))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = bg,
        border = BorderStroke(1.dp, Color(0xFF282A36)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                }
                Text(
                    text = "Linux PRoot (pty0)",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = textColor.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "tianyan@debian:~$ uname -a",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accentColor,
            )
            Text(
                text = "Linux tianyan 6.6.0-aarch64 #1 SMP PREEMPT GNU/Linux",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                ),
                color = textColor.copy(alpha = 0.9f),
            )
            Text(
                text = stringResource(R.string.settings_terminal_preview_command),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accentColor,
            )
            Text(
                text = stringResource(R.string.settings_terminal_preview_output),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                ),
                color = textColor,
            )
        }
    }
}

@Composable
internal fun TerminalThemeChip(
    title: String,
    bg: Color,
    text: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(0xFF282A36),
        ),
    ) {
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(text),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(10.dp), bg)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                color = text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

