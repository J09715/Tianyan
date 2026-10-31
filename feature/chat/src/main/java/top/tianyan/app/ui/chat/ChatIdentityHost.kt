package top.tianyan.app.ui.chat

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.ChatIdentityPreferences
import top.tianyan.app.core.model.ChatIdentityStyle
import top.tianyan.app.ui.components.*

private val LocalIdentityProvided = staticCompositionLocalOf { false }

@Composable
internal fun ChatIdentityHost(content: @Composable () -> Unit) {
    if (LocalIdentityProvided.current) { content(); return }
    val preferences = previewOrNull { koinInject<ChatIdentityPreferences>() }
    val style by preferences?.style?.collectAsStateWithLifecycle(ChatIdentityStyle()) ?: previewStateOf(ChatIdentityStyle())
    CompositionLocalProvider(LocalChatIdentity provides style, LocalIdentityProvided provides true, content = content)
}
