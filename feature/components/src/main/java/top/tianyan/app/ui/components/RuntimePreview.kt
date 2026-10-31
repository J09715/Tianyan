package top.tianyan.app.ui.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Devices
import top.tianyan.app.ui.theme.TianyanTheme

/** Device windows cover the outer display, an intermediate window and the large inner display.
 * These are layout previews; device presets do not inject a hardware hinge angle.
 */
@Preview(name = "折叠态 · 外屏", device = Devices.PHONE, showSystemUi = true, locale = "zh")
@Preview(name = "中间态 · 中等窗口", device = Devices.FOLDABLE, showSystemUi = true, locale = "zh")
@Preview(name = "展开态 · 内屏", device = Devices.PIXEL_9_PRO_FOLD, showSystemUi = true, locale = "zh")
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class FoldablePreviews

/** Do not create a DI graph or start ViewModel side effects in Layoutlib. */
@Composable
fun <T> previewOrNull(factory: @Composable () -> T): T? =
    if (LocalInspectionMode.current) null else factory()

@Composable
fun <T> previewStateOf(value: T): State<T> {
    check(LocalInspectionMode.current) { "Static page state is only available in Compose previews" }
    return remember(value) { mutableStateOf(value) }
}

@Composable
fun RuntimePreview(content: @Composable () -> Unit) {
    TianyanTheme(dynamicColor = false) {
        Surface(content = content)
    }
}
