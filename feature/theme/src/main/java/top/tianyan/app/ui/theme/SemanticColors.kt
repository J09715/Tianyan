package top.tianyan.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend

/**
 * 天衍 · M3 语义色扩展。
 *
 * Material You 的 [ColorScheme] 只定义 primary / secondary / tertiary
 * 三组彩色角色，无法表达「成功 / 警告 / 信息 / 工具 / 分支」这类领域语义。全App 曾直接写死
 * Tailwind 色值（#10B981 绿、#F59E0B 橙、#7C4DFF 紫），导致开启动态取色后容器跟随壁纸变化，
 * 而徽标与状态点仍是固定色，破坏 Material You 的一致性。
 *
 * 这里把领域色收敛为随明暗主题切换的一等语义 token：
 * - 不再硬编码，保证浅色 / 深色两套都有足够对比度；
 * - 与 colorScheme 同源切换，用户切换主题时不会残留旧色；
 * - 经 [buildSemanticColors] 按官方 harmonize 规则向当前主色靠拢（旋转 ≤15°、保持彩度与色调，
 *   对比度不劣化），实现语义色随动态色板谐调。
 */
@Immutable
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    /** 智能体 / 分支 / 子任务等「非常规业务对象」的标识色。 */
    val agent: Color,
    val onAgent: Color,
    val agentContainer: Color,
    val onAgentContainer: Color,
    /** 代码、日志、终端等等宽文本场景。 */
    val code: Color,
    val codeSurface: Color,
    /** 终端与日志区的专用底色，始终保持最深以保证可读性。 */
    val terminalSurface: Color,
    val terminalText: Color,
    /**
     * 数据可视化分类色板（上下文占用分段、图表系列等）。
     *
     * 分类色需要彼此可辨，允许超出 M3 三色角色，但必须：
     * 1. 明暗两套各自校准，保证在各自 surface 上满足对比度；
     * 2. 色相分布均匀，避免相邻系列撞色；
     * 3. 跟随主题切换，避免深色主题下浅色系过曝。
     */
    val categorical: List<Color>,
)

internal val LightSemantics = SemanticColors(
    success = Color(0xFF1B6B3A),
    onSuccess = Color.White,
    successContainer = Color(0xFFA8F5C6),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF8A5000),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB6),
    onWarningContainer = Color(0xFF2C1600),
    info = Color(0xFF00629B),
    onInfo = Color.White,
    infoContainer = Color(0xFFCFE5FF),
    onInfoContainer = Color(0xFF001D33),
    agent = Color(0xFF6B4EA8),
    onAgent = Color.White,
    agentContainer = Color(0xFFE9DDFF),
    onAgentContainer = Color(0xFF250057),
    code = Color(0xFF386A20),
    codeSurface = Color(0xFFF1EFF4),
    terminalSurface = Color(0xFF1C1B20),
    terminalText = Color(0xFFE6E1E5),
    categorical = listOf(
        Color(0xFF6B7280),
        Color(0xFF7C3AED),
        Color(0xFF15803D),
        Color(0xFFB45309),
        Color(0xFFA855F7),
        Color(0xFF0369A1),
        Color(0xFFBE123C),
        Color(0xFF8C4A60),
        Color(0xFF0F766E),
        Color(0xFF9D174D),
        Color(0xFF4338CA),
        Color(0xFF4D7C0F),
    ),
)

internal val DarkSemantics = SemanticColors(
    success = Color(0xFF8CD9A8),
    onSuccess = Color(0xFF00391D),
    successContainer = Color(0xFF00522C),
    onSuccessContainer = Color(0xFFA8F5C6),
    warning = Color(0xFFFFB868),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF693C00),
    onWarningContainer = Color(0xFFFFDDB6),
    info = Color(0xFF96CCFF),
    onInfo = Color(0xFF003354),
    infoContainer = Color(0xFF004A77),
    onInfoContainer = Color(0xFFCFE5FF),
    agent = Color(0xFFD3BBFF),
    onAgent = Color(0xFF3F1D78),
    agentContainer = Color(0xFF573590),
    onAgentContainer = Color(0xFFE9DDFF),
    code = Color(0xFF9FD97F),
    codeSurface = Color(0xFF1E1F25),
    terminalSurface = Color(0xFF0D0E13),
    terminalText = Color(0xFFE3E2E9),
    categorical = listOf(
        Color(0xFF9CA3AF),
        Color(0xFFD3BBFF),
        Color(0xFF8CD9A8),
        Color(0xFFFFB868),
        Color(0xFFE9C7FF),
        Color(0xFF96CCFF),
        Color(0xFFFFB4AB),
        Color(0xFFE6B8C9),
        Color(0xFF5EEAD4),
        Color(0xFFFDA4AF),
        Color(0xFFA5B4FC),
        Color(0xFFBEF264),
    ),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantics }

/**
 * 取当前主题下的语义色（[TianyanTheme] 已按当前材质色板谐调并通过 Local 提供）。
 */
@Composable
@ReadOnlyComposable
fun semanticColors(): SemanticColors = LocalSemanticColors.current

/**
 * 从当前 [ColorScheme] 构建语义色：明暗基准随 surface 亮度选择，
 * 色相经官方 harmonize 向主色靠拢（旋转 ≤15°、保持彩度与色调，WCAG 对比度不破坏）。
 *
 * code / 终端等功能性颜色保持固定：它们代表「代码」本身而非品牌身份，不应随壁纸漂移。
 */
internal fun buildSemanticColors(colorScheme: ColorScheme): SemanticColors {
    val base = if (colorScheme.surface.luminanceIsDark()) DarkSemantics else LightSemantics
    val source = colorScheme.primary.toArgb()

    fun Color.harmonized(): Color = Color(Blend.harmonize(toArgb(), source))

    return base.copy(
        success = base.success.harmonized(),
        onSuccess = base.onSuccess.harmonized(),
        successContainer = base.successContainer.harmonized(),
        onSuccessContainer = base.onSuccessContainer.harmonized(),
        warning = base.warning.harmonized(),
        onWarning = base.onWarning.harmonized(),
        warningContainer = base.warningContainer.harmonized(),
        onWarningContainer = base.onWarningContainer.harmonized(),
        info = base.info.harmonized(),
        onInfo = base.onInfo.harmonized(),
        infoContainer = base.infoContainer.harmonized(),
        onInfoContainer = base.onInfoContainer.harmonized(),
        agent = base.agent.harmonized(),
        onAgent = base.onAgent.harmonized(),
        agentContainer = base.agentContainer.harmonized(),
        onAgentContainer = base.onAgentContainer.harmonized(),
        categorical = base.categorical.map { it.harmonized() },
    )
}

private fun Color.luminanceIsDark(): Boolean = luminance() < 0.5f