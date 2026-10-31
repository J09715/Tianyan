package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared large-title list shell, including centered wide layouts and keyboard-safe scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    itemSpacing: Dp = 16.dp,
    statusText: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { RuntimeTopBar(title, onBack, scrollBehavior, actions = actions) },
        snackbarHost = snackbarHost,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(runtimeScrollViewport(padding)).imePadding(),
            contentPadding = runtimeScrollContentPadding(padding,
                runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 32.dp)),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = {
                if (!statusText.isNullOrBlank()) item("runtime-settings-status") {
                    Text(statusText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            },
        )
    }
}
