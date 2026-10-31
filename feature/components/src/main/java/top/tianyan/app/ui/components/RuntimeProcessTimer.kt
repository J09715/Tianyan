package top.tianyan.app.ui.components

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.util.Locale
import kotlinx.coroutines.delay

fun formatRuntimeProcessDuration(ms: Long): String =
    String.format(Locale.US, "%.1fs", (ms.coerceAtLeast(0L) / 100) / 10.0)

/** Tick only the small time label, without recomposing the reasoning Markdown every 100 ms. */
@Composable
fun RuntimeProcessTimer(id: String, running: Boolean, startedAtMs: Long? = null, durationMs: Long? = null) {
    var anchor by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var elapsed by rememberSaveable(id) { mutableLongStateOf(0L) }
    LaunchedEffect(id, running, startedAtMs) {
        if (running) {
            if (anchor == null) anchor = SystemClock.elapsedRealtime() -
                (startedAtMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0L)
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - checkNotNull(anchor)).coerceAtLeast(0)
                delay(100)
            }
        }
    }
    if (running || durationMs != null || anchor != null) Text(
        formatRuntimeProcessDuration(if (running) elapsed else durationMs ?: elapsed),
        // Avoid announcing ten updates a second inside the first-response live region.
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = runtimeProcessColor(), maxLines = 1,
    )
}
