package top.tianyan.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Default settings density; system font scaling still applies normally. */
internal object SettingsDensity {
    val rowMinHeight = 80.dp

    /** Home-page navigation entries: tonal icon tile, still above the M3 72dp two-line target. */
    val navRowMinHeight = 88.dp
    val navTileSize = 40.dp
    val navIconSize = 20.dp
    val rowHorizontalPadding = 20.dp
    val rowVerticalPadding = 14.dp
    val iconSize = 24.dp
    val iconGap = 12.dp
    val textGap = 4.dp
    val cardPadding = 16.dp
    val sectionGap = 20.dp
}

@Composable
internal fun settingsTitleStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    lineHeight = 22.sp, letterSpacing = 0.sp,
)

@Composable
internal fun settingsSupportingStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    lineHeight = 20.sp, letterSpacing = 0.sp,
)
