package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.ThemePalette
import top.tianyan.app.ui.theme.TianyanTheme

@Preview(name = "外观 · 玫瑰色", widthDp = 360, locale = "zh")
@Preview(name = "外观 · 深色", widthDp = 360, locale = "zh", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "外观 · 大字体", widthDp = 320, fontScale = 1.5f, locale = "zh")
@Composable
private fun ThemePalettePickerPreview() {
    val palette = ThemePalette(colorId = "rose")
    TianyanTheme(dynamicColor = false, palette = palette) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PersonalColorPreview()
                ThemePalettePicker(palette, false, "system", {}, {}, {})
            }
        }
    }
}
