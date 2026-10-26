package top.tianyan.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.theme.TianyanTheme

@Preview(name = "Expressive · compact", widthDp = 320, locale = "zh")
@Preview(name = "Expressive · large font", widthDp = 320, fontScale = 1.3f, locale = "zh")
@Preview(name = "Expressive · dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RuntimeButtonsPreview() {
    TianyanTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeButton(onClick = {}) { Text("开始") }
                    RuntimeFilledTonalButton(onClick = {}) { Text("编辑配置") }
                    RuntimeOutlinedButton(onClick = {}) { Text("导入") }
                    RuntimeTextButton(onClick = {}) { Text("取消") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeButton(onClick = {}, enabled = false) { Text("暂不可用") }
                    RuntimeIconButton(onClick = {}, contentDescription = "添加") {
                        RuntimeIcon(name = RuntimeIconName.Plus)
                    }
                }
            }
        }
    }
}
