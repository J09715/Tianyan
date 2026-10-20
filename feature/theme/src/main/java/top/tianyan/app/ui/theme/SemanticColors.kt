package top.tianyan.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 「天衍 · Tianyan」语义色令牌（Design Tokens · 语义层）。
 *
 * ## 为什么需要这个文件
 *
 * 引入前 `feature` 各模块里有 533 处 `Color(0x…)`，其中语义色高度重复：
 * `0xFF10B981`（成功绿）28 次、`0xFFF59E0B`（警告橙）23 次、`0xFF2E7D32`（深绿）21 次、
 * `0xFF7C4DFF`（紫）17 次、`0xFF8B5CF6` 13 次、`0xFF3B82F6` 11 次……
 *
 * 这些颜色有两个问题：
 *
 * 1. **绕过双主题**。它们是不随主题变化的绝对值，在「玄同」浅色下偏艳、在「澄明」
 *    液态玻璃的深色底上偏灰，同一屏里与 MaterialTheme 派生的颜色并置会明显不协调。
 * 2. **同一语义多个值**。成功状态在 settings 里是 `2E7D32`、在 workspace 里是
 *    `10B981`，两屏并排看是两个绿。
 *
 * 本文件把「语义」与「具体色值」解耦：调用方只表达意图（成功/警告/危险/信息/中性），
 * 具体值按当前主题的明暗自动选变体。与既有的 `settings/StatusColors.kt`、
 * `home/healthyStatusColor()` 是同一思路，此处收敛为唯一来源。
 *
 * ## 为什么用 CompositionLocal 而不是 @Composable getter
 *
 * 明暗判定在主题装配时算一次，而不是每个调用点各算一次；也让「将来支持用户自定义
 * 语义色」只需改注入侧，不动任何调用点。
 *
 * ## 使用约定
 *
 * - 状态类着色一律走 [TianyanTheme.colors]；不要直接写 `Color(0xFF...)`。
 * - 品牌/分类色（插件青蓝、用户目录蓝灰等）属于「分类标识」而非「状态」，
 *   见 `accent*` 系列。
 */
@Immutable
data class TianyanColors(
    /** 成功 / 健康 / 就绪。 */
    val success: Color,
    /** 警告 / 待处理 / 降级。 */
    val warning: Color,
    /** 危险 / 失败 / 被封禁。 */
    val danger: Color,
    /** 信息 / 进行中 / 提示。 */
    val info: Color,
    /** 中性 / 未启用 / 未知。 */
    val neutral: Color,

    /** 紫 — 智能体 / 技能。 */
    val accentViolet: Color,
    /** 蓝 — 模型 / 网络。 */
    val accentBlue: Color,
    /** 青 — 插件 / 扩展生态。 */
    val accentCyan: Color,
    /** 靛 — 工作区 / 项目。 */
    val accentIndigo: Color,
    /** 蓝灰 — 用户数据 / 家目录。 */
    val accentSlate: Color,
    /** 珊瑚 — 需注意的破坏性入口（删除、重置）。 */
    val accentCoral: Color,

    /**
     * 底衬透明度：深色底上需要更高不透明度才看得出色块，浅色底上低一些才不显脏。
     */
    val containerAlpha: Float,
) {
    /**
     * 由基色派生低透明度底衬，用于图标背景。
     *
     * 卡片统一走这个而不是各自 `.copy(alpha = 0.12f)`：透明度值散落时，
     * 相邻两张卡的底衬深浅会不一致，视觉上像是层级不同。
     */
    fun container(color: Color): Color = color.copy(alpha = containerAlpha)
}

/**
 * 深色主题的语义色：取高亮度变体，保证在近黑背景上有足够对比。
 */
internal val DarkTianyanColors = TianyanColors(
    success = Color(0xFF6EE7B7),
    warning = Color(0xFFFCD34D),
    danger = Color(0xFFFCA5A5),
    info = Color(0xFF93C5FD),
    neutral = Color(0xFF94A3B8),
    accentViolet = Color(0xFFC4B5FD),
    accentBlue = Color(0xFF93C5FD),
    accentCyan = Color(0xFF67E8F9),
    accentIndigo = Color(0xFFA5B4FC),
    accentSlate = Color(0xFFCBD5E1),
    accentCoral = Color(0xFFFDA4AF),
    containerAlpha = 0.18f,
)

/**
 * 浅色主题的语义色：取低亮度变体，避免在素白底上刺眼。
 */
internal val LightTianyanColors = TianyanColors(
    success = Color(0xFF047857),
    warning = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    info = Color(0xFF1D4ED8),
    neutral = Color(0xFF475569),
    accentViolet = Color(0xFF6D28D9),
    accentBlue = Color(0xFF1D4ED8),
    accentCyan = Color(0xFF0E7490),
    accentIndigo = Color(0xFF4338CA),
    accentSlate = Color(0xFF475569),
    accentCoral = Color(0xFFBE123C),
    containerAlpha = 0.12f,
)

/**
 * 当前主题的语义色。
 *
 * 由 [TianyanTheme] 注入；未包裹在主题内的预览会拿到浅色默认值（不会崩）。
 */
val LocalTianyanColors = staticCompositionLocalOf { LightTianyanColors }

/**
 * 语义色入口，与 [MaterialTheme] 同构的写法：`TianyanTheme.colors.success`。
 *
 * 与同名 Composable 函数 [TianyanTheme] 共存不冲突（Kotlin 的 classifier 与
 * function 分属不同命名空间），Material3 自身就是这么组织的。
 */
object TianyanTheme {
    val colors: TianyanColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTianyanColors.current
}
