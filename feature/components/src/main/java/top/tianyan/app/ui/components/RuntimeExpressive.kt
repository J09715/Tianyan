package top.tianyan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonElevation
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.theme.Touch

/**
 * 「天衍 · Tianyan」M3 Expressive 组件层。
 *
 * ## 为什么单独成文件
 *
 * `RuntimeComponents.kt` 已经是 1650 行、承载 M3 基线组件（Card/Button/Dialog/…）。
 * Expressive 组件来自 material3 **1.5.0-beta01**（BOM 锁定的是 1.4.0，见
 * `libs.versions.toml` 中 material3 条目的说明），处于 beta 期，API 可能变动。
 *
 * 把 beta 组件单独收在一个文件里，是为了让「beta 改 API」这件事的爆炸半径只有这一个
 * 文件：业务屏只依赖这里的 `Runtime*` 包装，不直接 import `androidx.compose.material3`
 * 的实验性组件。将来 material3 转正、或需要回退到 1.4.0 时，改这里即可。
 *
 * ## 双主题
 *
 * 玄同（标准 M3）与澄明（液态玻璃）都要能用。Expressive 组件本身由 MaterialTheme
 * 派生颜色，因此不额外做玻璃化——玻璃效果由外层 `RuntimeCard` / `RuntimeButton` 承担，
 * 这里只保证在两种主题下颜色都跟得上。
 *
 * ## 实验性 API
 *
 * 文件级 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`：让调用方不必逐个加
 * 注解，同时把「本文件依赖实验性 API」这个事实钉在文件头，升级时一眼能看见。
 *
 * ## 命名注意
 *
 * 包装函数与 material3 同名（`ToggleButton` / `LoadingIndicator`）。函数体内一律用
 * 全限定名调用库函数，否则会递归调用自己——这是同名包装最容易踩的坑。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object RuntimeExpressive {

    /**
     * 切换按钮：用于「多选一组的开关」场景（如筛选条件、模式切换）。
     *
     * 与 `RuntimeSwitch` 的分工：Switch 表达「开/关」，ToggleButton 表达「选中/未选中」
     * 且本身是一个可点按钮。两者不要互相替代——用 Switch 做多选一，无障碍上会被读成
     * 「开关」而不是「选项」。
     *
     * @param checked 当前是否选中
     * @param onCheckedChange 点击回调
     * @param icon 可选前置图标（传 null 时内容区从左侧开始）
     * @param size 尺寸档位，默认 Small，密集列表用 ExtraSmall
     */
    @Composable
    fun ToggleButton(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        size: ToggleButtonSize = ToggleButtonDefaults.size,
        icon: (@Composable () -> Unit)? = null,
        shapes: ToggleButtonShapes = ToggleButtonDefaults.shapesFor(size),
        colors: ToggleButtonColors = ToggleButtonDefaults.colors(),
        elevation: ToggleButtonElevation? = ToggleButtonDefaults.elevation(),
        border: BorderStroke? = null,
        contentPadding: PaddingValues =
            ToggleButtonDefaults.contentPaddingFor(size, hasStartIcon = icon != null),
        interactionSource: MutableInteractionSource? = null,
        content: @Composable RowScope.() -> Unit,
    ) {
        androidx.compose.material3.ToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            buttonSize = size,
            enabled = enabled,
            icon = icon,
            shapes = shapes,
            colors = colors,
            elevation = elevation,
            border = border,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )
    }

    /**
     * 不定量加载指示器（Expressive 的形变多边形动画）。
     *
     * 与 [InlineSpinner] 的分工：
     * - 这里是「页面级/区块级正在加载」，视觉更抢眼，适合首屏骨架加载与空态等待；
     * - [InlineSpinner] 是「行内小转圈」，适合按钮内、列表项内。
     *
     * 尺寸默认 48dp，与触摸目标下限一致；颜色默认取主题主色。
     */
    @Composable
    fun LoadingIndicator(
        modifier: Modifier = Modifier,
        color: Color = MaterialTheme.colorScheme.primary,
        size: Dp = Touch.min,
    ) {
        androidx.compose.material3.LoadingIndicator(
            modifier = modifier.size(size),
            color = color,
        )
    }

    /**
     * 定量加载指示器：进度 0f..1f。
     *
     * 形变随进度推进，比线性进度条更适合「一次性长任务」（RootFS 解压、APK 构建）。
     * 高频更新的进度（每秒数十次）不要用这个——形变动画本身有成本，
     * 那种场景用 `RuntimeLinearProgressIndicator`。
     */
    @Composable
    fun ProgressLoadingIndicator(
        progress: () -> Float,
        modifier: Modifier = Modifier,
        color: Color = MaterialTheme.colorScheme.primary,
        size: Dp = 64.dp,
    ) {
        androidx.compose.material3.LoadingIndicator(
            progress = progress,
            modifier = modifier.size(size),
            color = color,
        )
    }

    /**
     * 行内小转圈：保留 M3 基线的 CircularProgressIndicator，统一默认尺寸与描边。
     *
     * 之所以和上面两个指示器放在同一个文件：它们要放在一起比较着选，
     * 分开放会让人忘了有 Expressive 版本可用。
     */
    @Composable
    fun InlineSpinner(
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
        color: Color = MaterialTheme.colorScheme.primary,
        strokeWidth: Dp = 2.dp,
    ) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}
