package top.tianyan.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 终端按键栏：两行纯文字平面键（同 Termux 风格，无键帽底色），等宽铺满、窄屏按比例收窄。
 * CTRL/ALT 激活时以细描边 + 全亮文字提示；上下各留空隙，不贴分割线与导航栏。
 */
@Composable
internal fun TerminalExtraKeys(bridge: TianyanTerminalBridge, hapticsEnabled: Boolean) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp)
            // 与上方分割线、下方导航栏各留一口呼吸空隙
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Row1: ESC TAB / - ~ HOME ▲ END
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExtraKey("ESC", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendEscape()
            }
            ExtraKey("TAB", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendTab()
            }
            ExtraKey("/", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendString("/")
            }
            ExtraKey("-", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendString("-")
            }
            ExtraKey("~", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendString("~")
            }
            ExtraKey("HOME", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendHome()
            }
            ExtraKey("▲", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendArrowUp()
            }
            ExtraKey("END", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendEnd()
            }
        }
        // Row2: CTRL ALT | ^C ^D ◀ ▼ ▶
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExtraKey(
                "CTRL",
                Modifier.weight(1f),
                isActive = ctrlActive,
                hapticsEnabled = hapticsEnabled,
            ) {
                bridge.toggleControlKey()
                ctrlActive = bridge.isControlKeyActive()
            }
            ExtraKey(
                "ALT",
                Modifier.weight(1f),
                isActive = altActive,
                hapticsEnabled = hapticsEnabled,
            ) {
                bridge.toggleAltKey()
                altActive = bridge.isAltKeyActive()
            }
            ExtraKey("|", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendString("|")
            }
            ExtraKey("^C", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendSigInt()
            }
            ExtraKey("^D", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendEof()
            }
            ExtraKey("◀", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendArrowLeft()
            }
            ExtraKey("▼", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendArrowDown()
            }
            ExtraKey("▶", Modifier.weight(1f), hapticsEnabled = hapticsEnabled) {
                bridge.sendArrowRight()
            }
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    hapticsEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.then(
            if (label == "CTRL" || label == "ALT") Modifier.semantics { selected = isActive } else Modifier,
        ),
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        border = if (isActive) BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)) else null,
    ) {
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = Color.White.copy(alpha = if (isActive) 1f else 0.90f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
