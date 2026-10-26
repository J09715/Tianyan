package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import top.tianyan.app.ui.components.RuntimeCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.RuntimeFilledTextField
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** Consistent detail-page surface without increasing the default content density. */
@Composable
internal fun SettingsDetailCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = Color.Transparent,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(SettingsDensity.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) = RuntimeCard(modifier, containerColor, borderColor, shape, onClick, contentPadding, content)

@Composable
internal fun SettingsDialogForm(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Give the editable port a complete row, including on narrow windows and large fonts. */
@Composable
internal fun SettingsPortEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    supportingText: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RuntimeFilledTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(),
            label = { Text(label) }, isError = isError, singleLine = true,
            supportingText = { Text(supportingText) },
            leadingIcon = { RuntimeIcon(RuntimeIconName.Network, Modifier.size(20.dp)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), content = actions)
    }
}
