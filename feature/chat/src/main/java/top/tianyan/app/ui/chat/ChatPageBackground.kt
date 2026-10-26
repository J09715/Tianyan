package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.AppearancePreferences
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.datastore.ChatBackgroundPreferences
import top.tianyan.app.core.model.ChatBackgroundStyle
import top.tianyan.app.ui.components.RuntimeChatBackground
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.theme.LocalChatBackgroundActive
import top.tianyan.app.ui.theme.chatBackdropSurface

class ChatBackgroundViewModel(preferences: ChatBackgroundPreferences) : ViewModel() {
    val style = preferences.style.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatBackgroundStyle())
}

@Composable
internal fun ChatPageSurface(
    modifier: Modifier = Modifier,
    viewModel: ChatBackgroundViewModel? = previewOrNull { koinViewModel() },
    content: @Composable () -> Unit,
) {
    val style by viewModel?.style?.collectAsStateWithLifecycle() ?: previewStateOf(ChatBackgroundStyle())
    val appearance = previewOrNull { koinInject<AppearancePreferences>() }
    val classicDots by appearance?.classicToolStatusDots?.collectAsStateWithLifecycle(initialValue = false) ?: previewStateOf(false)
    val normalized = style.normalized()
    CompositionLocalProvider(LocalChatBackgroundActive provides
        (normalized.imageName != null && normalized.visibility > 0f), LocalClassicToolStatusDots provides classicDots) {
        // One continuous image behind the top bar, conversation, composer and system insets.
        Box(modifier) {
            RuntimeChatBackground(normalized, Modifier.matchParentSize())
            content()
        }
    }
}

/** Plain assistant text needs a readable surface on arbitrary light/dark photos. */
@Composable
internal fun ChatReadableMessage(content: @Composable ColumnScope.() -> Unit) {
    if (LocalChatBackgroundActive.current) {
        RuntimeCard(
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            containerColor = chatBackdropSurface(), contentPadding = PaddingValues(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun chatGreetingModifier(): Modifier = if (LocalChatBackgroundActive.current) {
    Modifier.background(chatBackdropSurface(), MaterialTheme.shapes.medium)
        .padding(horizontal = 12.dp, vertical = 8.dp)
} else Modifier
