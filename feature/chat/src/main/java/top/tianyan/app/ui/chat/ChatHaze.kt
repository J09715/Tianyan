package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import top.tianyan.app.core.datastore.ChatBackgroundPreferences

/** 聊天毛玻璃的共享 HazeState：source 挂在 [ChatPageSurface] 的聊天背景上，效果挂在输入区容器上。 */
val LocalChatHazeState = staticCompositionLocalOf { HazeState() }

/** 设置里的"输入区毛玻璃"开关；预览/测试环境 Koin 未启动时恒为关。 */
@Composable
internal fun rememberChatInputBlurEnabled(): Boolean {
    // Koin 读取放在 remember 计算里（非 composable 调用），缺容器时静默回退
    val preferences = remember {
        runCatching { org.koin.core.context.GlobalContext.get().get<ChatBackgroundPreferences>() }.getOrNull()
    } ?: return false
    val enabled by preferences.inputBlurEnabled.collectAsStateWithLifecycle(initialValue = false)
    return enabled
}

/**
 * 输入区容器背景效果（参考 rikkahub chatInputHaze）：开关关闭时原样返回；
 * 开启时容器自身需保持透明，模糊铺底与着色由效果负责。
 */
@Composable
internal fun Modifier.chatInputHaze(hazeState: HazeState, enabled: Boolean): Modifier {
    if (!enabled) return this
    return hazeBlur(
        input = HazeInput.Sources(hazeState),
        style = HazeBlurStyle.Material3 {
            blurRadius(12.dp)
        },
    )
}
