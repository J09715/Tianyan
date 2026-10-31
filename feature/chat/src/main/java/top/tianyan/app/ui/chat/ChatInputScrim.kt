package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import top.tianyan.app.ui.theme.chatBackdropChrome
import top.tianyan.app.ui.theme.LocalChatBackgroundActive

/**
 * 输入区底部渐变遮罩（参考 rikkahub ChatInput）：从透明渐变到聊天底色，
 * 消息滚动到输入区下方时不会生硬穿出；键盘弹起时实色区域正好补齐键盘上沿
 * 到内容之间的空隙。自定义背景时不绘制，让输入区外及系统导航栏直接透出全屏背景。
 */
@Composable
internal fun Modifier.chatInputScrim(): Modifier {
    if (LocalChatBackgroundActive.current) return this
    val scrimColor = chatBackdropChrome()
    val imeInsets = WindowInsets.ime
    return drawWithCache {
        val brush = Brush.verticalGradient(
            colors = listOf(scrimColor.copy(alpha = 0f), scrimColor),
            startY = 0f,
            endY = (size.height - imeInsets.getBottom(this)).coerceAtLeast(1f),
        )
        onDrawBehind { drawRect(brush) }
    }
}
