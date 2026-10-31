package top.tianyan.app.ui.settings

import top.tianyan.app.ui.components.RuntimeIllustration
import top.tianyan.app.ui.components.RuntimeIllustrationScene
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.settings.R

/** Decorative illustration uses the active palette, including wallpaper colors. */
@Composable
internal fun PersonalColorPreview() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RuntimeIllustration(RuntimeIllustrationScene.Appearance)
        Text(stringResource(R.string.personal_color_intro), modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}
