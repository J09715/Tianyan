package top.tianyan.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Drawer · Compact", widthDp = 280, heightDp = 180, locale = "zh")
@Preview(name = "Drawer · Large text", widthDp = 320, heightDp = 220, fontScale = 1.3f, locale = "zh")
@Preview(name = "Drawer · English dark", widthDp = 320, heightDp = 180, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppNavigationSectionPreview() {
    RuntimePreview {
        AppNavigationSection(
            selected = MainDestination.Home,
            onNavigate = {}, onOpenTerminal = {}, onOpenTools = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
