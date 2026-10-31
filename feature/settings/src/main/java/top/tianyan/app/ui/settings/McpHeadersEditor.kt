package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

@Composable
internal fun McpHeadersEditor(value: String, onChange: (String) -> Unit, error: String?) {
    var visible by remember { mutableStateOf(false) }
    Column {
        RuntimeFilledTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("HTTP 请求头（JSON，可选）") },
            supportingText = { Text(error ?: "例如 Authorization: Bearer <令牌>，以 JSON 对象填写；加密保存且不导出") },
            isError = error != null,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        RuntimeTextButton(onClick = { visible = !visible }) {
            Text(if (visible) "隐藏请求头" else "显示请求头", style = MaterialTheme.typography.labelSmall)
        }
    }
}
