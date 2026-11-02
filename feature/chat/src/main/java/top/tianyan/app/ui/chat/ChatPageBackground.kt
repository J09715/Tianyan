package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
    val hazeState = rememberHazeState()
    ChatIdentityHost {
        CompositionLocalProvider(LocalChatBackgroundActive provides
            (normalized.imageName != null && normalized.visibility > 0f), LocalClassicToolStatusDots provides classicDots,
            LocalChatHazeState provides hazeState) {
            // One continuous image behind the top bar, conversation, composer and system insets.
            Box(modifier) {
                RuntimeChatBackground(normalized, Modifier.matchParentSize().hazeSource(hazeState))
                content()
            }
        }
    }
}

/**
 * 壁纸模式的消息容器样式：纯文本回复、工具链、工具卡、用户气泡共用同一圆角与内边距。
 *
 * 开壁纸后原来各写一套（28dp / shapes.medium / 16dp；16 / 12 / 0dp 内距），
 * 同一列表出现三种左边缘和三种圆角，滚动时块边界来回跳。这里收口成一组常量，
 * 内缩只作用于背景块内部（padding 在 background 之后），不改变布局宽度。
 */
internal val ChatMessageContainerShape = RoundedCornerShape(24.dp)
internal val ChatMessageContainerPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** Plain assistant text needs a readable surface on arbitrary light/dark photos. */
@Composable
internal fun ChatReadableMessage(content: @Composable ColumnScope.() -> Unit) {
    if (LocalChatBackgroundActive.current) {
        RuntimeCard(
            modifier = Modifier.fillMaxWidth(), shape = ChatMessageContainerShape,
            containerColor = chatBackdropSurface(), contentPadding = ChatMessageContainerPadding,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** 工具链/工具卡/问候语在壁纸模式下的统一容器（与纯文本回复同一圆角、同一内边距）。 */
@Composable
internal fun Modifier.chatMessageContainer(): Modifier = if (LocalChatBackgroundActive.current) {
    background(chatBackdropSurface(), ChatMessageContainerShape).padding(ChatMessageContainerPadding)
} else this
