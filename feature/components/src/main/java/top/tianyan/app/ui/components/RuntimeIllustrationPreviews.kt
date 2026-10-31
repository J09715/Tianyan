package top.tianyan.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Illustrations · Light", widthDp = 320, heightDp = 850)
@Preview(name = "Illustrations · Dark", widthDp = 320, heightDp = 850,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RuntimeIllustrationPreview() {
    RuntimePreview {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeIllustrationScene.entries.forEach { RuntimeIllustration(it) }
        }
    }
}

@Preview(name = "Usage notice · Large text", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "Usage notice · Dark", widthDp = 360, heightDp = 800, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RuntimeUsageNoticePreview() {
    RuntimePreview { RuntimeUsageNoticeDialog(onDismiss = {}) }
}
