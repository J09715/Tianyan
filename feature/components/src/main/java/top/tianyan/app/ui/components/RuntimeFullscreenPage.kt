package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-window detail page above the current editor, keeping its unsaved state in place. */
@Composable
fun RuntimeFullscreenPage(title: String, onBack: () -> Unit, statusText: String? = null,
    content: @Composable (PaddingValues) -> Unit) {
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RuntimeDialogSystemBars()
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            RuntimeTopBar(title = title, onBack = onBack, statusText = statusText)
        }, content = content)
    }
}
