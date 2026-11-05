package top.tianyan.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 「天衍 · Tianyan」字阶令牌（Design Tokens · 文字层）。
 *
 * ## 为什么不再直接用 [Typography]
 *
 * M3 默认字阶是按拉丁字母标定的：`bodySmall = 12sp / lineHeight 16sp / letterSpacing 0.4sp`。
 * 中文没有 x-height 与上下伸部，同字号下的视觉体积比拉丁小一档；行高 16sp 会让两行中文挤在
 * 一起；0.4–0.5sp 的拉丁字距落在汉字之间，会均匀地拉出缝隙，读起来像散架。
 *
 * 这个偏差被全仓的实际用法放大：
 *
 * ```
 * typography.bodySmall  558 处
 * typography.labelSmall 516 处
 * ```
 *
 * 1074 处界面文字落在两个最小、最紧的字阶上——这是「能用但显得廉价」的主要来源，
 * 而不是配色。
 *
 * ## 本版口径（遵守 ARCHITECTURE_RULES §3 第 51 条）
 *
 * - **不动任何角色的 font weight**：字重严格保持 M3 规范值（title 400/500、label 500、
 *   body 400），层级由字级与留白承载，局部加粗仍在调用点用 `fontWeight` 覆盖；
 * - 只做「字体族 / 字号 / 行高 / 字距」的微调，且集中在正文层；
 * - 中文行内字距归零，仅 label 保留 0.2sp 作界面点缀；
 * - 全部字阶补 [LineHeightStyle.Trim.None]，避免中文首行上伸部被行盒裁切；
 * - 数字等宽（`tnum`）由调用点按需开启，不在字阶里全局注入。
 */
private val CjkLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun cjkStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Float,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = CjkLineHeightStyle,
)

/**
 * 字级对照（左=M3 默认，右=天衍）：
 *
 * | 角色 | M3 默认 | 天衍 | 理由 |
 * |---|---|---|---|
 * | displayLarge | 57/64 | 57/66 | 中文大标题需要更松的行盒 |
 * | headlineLarge | 32/40 | 32/42 | 同上 |
 * | titleLarge | 22/28 | 22/30 | 卡片标题中文易糊行 |
 * | bodyLarge | 16/24 +0.5 | 16/26 +0 | 中文正文行距 1.6 |
 * | bodyMedium | 14/20 +0.25 | 14/22 +0 | 说明文字主战场（231 处） |
 * | bodySmall | 12/16 +0.4 | 13/20 +0 | 使用最多（558 处），+1sp 直接可读性 |
 * | labelSmall | 11/16 +0.5 | 11/16 +0.2 | 徽章与辅助标签，去掉字距裂缝 |
 */
val AppTypography = Typography(
    displayLarge = cjkStyle(57, 66, FontWeight.Normal, 0f),
    displayMedium = cjkStyle(45, 54, FontWeight.Normal, 0f),
    displaySmall = cjkStyle(36, 45, FontWeight.Normal, 0f),

    headlineLarge = cjkStyle(32, 42, FontWeight.Normal, 0f),
    headlineMedium = cjkStyle(28, 37, FontWeight.Normal, 0f),
    headlineSmall = cjkStyle(24, 32, FontWeight.Normal, 0f),

    titleLarge = cjkStyle(22, 30, FontWeight.Normal, 0f),
    titleMedium = cjkStyle(16, 24, FontWeight.Medium, 0f),
    titleSmall = cjkStyle(14, 21, FontWeight.Medium, 0f),

    bodyLarge = cjkStyle(16, 26, FontWeight.Normal, 0f),
    bodyMedium = cjkStyle(14, 22, FontWeight.Normal, 0f),
    bodySmall = cjkStyle(13, 20, FontWeight.Normal, 0f),

    labelLarge = cjkStyle(14, 20, FontWeight.Medium, 0.2f),
    labelMedium = cjkStyle(12, 17, FontWeight.Medium, 0.2f),
    labelSmall = cjkStyle(11, 16, FontWeight.Medium, 0.2f),
)
