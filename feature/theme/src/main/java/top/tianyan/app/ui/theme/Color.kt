package top.tianyan.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「天衍 · Tianyan」Material 3 Expressive 设计系统色板
 * 融合 Google M3 Expressive 的生动高阶色调：
 * 曜石夜空（Obsidian Expressive Dark）与 温润素白（Alabaster Expressive Light）
 */

// M3 Expressive 主色系 (Indigo / Ultramarine)
val M3ExpLightPrimary = Color(0xFF4259C3)
val M3ExpLightOnPrimary = Color(0xFFFFFFFF)
val M3ExpLightPrimaryContainer = Color(0xFFDFE1FF)
val M3ExpLightOnPrimaryContainer = Color(0xFF001453)

val M3ExpDarkPrimary = Color(0xFFBAC3FF)
val M3ExpDarkOnPrimary = Color(0xFF0C2792)
val M3ExpDarkPrimaryContainer = Color(0xFF293FA9)
val M3ExpDarkOnPrimaryContainer = Color(0xFFDFE1FF)

// M3 Expressive 次色系 (Expressive Slate Iris)
val M3ExpLightSecondary = Color(0xFF5B5D72)
val M3ExpLightOnSecondary = Color(0xFFFFFFFF)
val M3ExpLightSecondaryContainer = Color(0xFFDFE1F9)
val M3ExpLightOnSecondaryContainer = Color(0xFF181A2C)

val M3ExpDarkSecondary = Color(0xFFC3C5DD)
val M3ExpDarkOnSecondary = Color(0xFF2C2F42)
val M3ExpDarkSecondaryContainer = Color(0xFF434559)
val M3ExpDarkOnSecondaryContainer = Color(0xFFDFE1F9)

// M3 Expressive 三级色 / 强调色 (Terracotta Coral / Rose)
val M3ExpLightTertiary = Color(0xFF944A32)
val M3ExpLightOnTertiary = Color(0xFFFFFFFF)
val M3ExpLightTertiaryContainer = Color(0xFFFFDBD1)
val M3ExpLightOnTertiaryContainer = Color(0xFF3B0900)

val M3ExpDarkTertiary = Color(0xFFFFB5A0)
val M3ExpDarkOnTertiary = Color(0xFF5A1C08)
val M3ExpDarkTertiaryContainer = Color(0xFF77321D)
val M3ExpDarkOnTertiaryContainer = Color(0xFFFFDBD1)

// M3 Expressive 状态色 (Semantic Feedback)
val M3ExpLightError = Color(0xFFBA1A1A)
val M3ExpLightErrorContainer = Color(0xFFFFDAD6)
val M3ExpDarkError = Color(0xFFFFB4AB)
val M3ExpDarkErrorContainer = Color(0xFF93000A)

// M3 反向色 / 固定强调色 (Inverse & Fixed Accent，固定角色深浅模式同值)
val M3ExpLightInversePrimary = Color(0xFFBAC3FF)
val M3ExpDarkInversePrimary = Color(0xFF4259C3)
val M3ExpLightInverseSurface = Color(0xFF2F3036)
val M3ExpLightInverseOnSurface = Color(0xFFF1F0F7)
val M3ExpDarkInverseSurface = Color(0xFFE3E2E9)
val M3ExpDarkInverseOnSurface = Color(0xFF2F3036)
val M3ExpScrim = Color(0xFF000000)
val M3ExpLightSurfaceTint = Color(0xFF4259C3)
val M3ExpDarkSurfaceTint = Color(0xFFBAC3FF)
val M3ExpLightSurfaceBright = Color(0xFFFAF8FD)
val M3ExpLightSurfaceDim = Color(0xFFDAD9E0)
val M3ExpDarkSurfaceBright = Color(0xFF3A3B44)
val M3ExpDarkSurfaceDim = Color(0xFF0A0B10)

val M3ExpPrimaryFixed = Color(0xFFDFE1FF)
val M3ExpPrimaryFixedDim = Color(0xFFBAC3FF)
val M3ExpOnPrimaryFixed = Color(0xFF001453)
val M3ExpOnPrimaryFixedVariant = Color(0xFF293FA9)
val M3ExpSecondaryFixed = Color(0xFFDFE1F9)
val M3ExpSecondaryFixedDim = Color(0xFFC3C5DD)
val M3ExpOnSecondaryFixed = Color(0xFF181A2C)
val M3ExpOnSecondaryFixedVariant = Color(0xFF434559)
val M3ExpTertiaryFixed = Color(0xFFFFDBD1)
val M3ExpTertiaryFixedDim = Color(0xFFFFB5A0)
val M3ExpOnTertiaryFixed = Color(0xFF3B0900)
val M3ExpOnTertiaryFixedVariant = Color(0xFF77321D)

// M3 Expressive 浅色表面层级 (Alabaster Warm Layers)
val M3ExpLightBackground = Color(0xFFFAF8FD)
val M3ExpLightOnBackground = Color(0xFF1A1B21)
val M3ExpLightSurface = Color(0xFFFAF8FD)
val M3ExpLightOnSurface = Color(0xFF1A1B21)
val M3ExpLightSurfaceVariant = Color(0xFFE2E2EC)
val M3ExpLightOnSurfaceVariant = Color(0xFF45464F)
val M3ExpLightSurfaceContainerLowest = Color(0xFFFFFFFF)
val M3ExpLightSurfaceContainerLow = Color(0xFFF4F2F8)
val M3ExpLightSurfaceContainer = Color(0xFFEEEBF2)
val M3ExpLightSurfaceContainerHigh = Color(0xFFE8E5EC)
val M3ExpLightSurfaceContainerHighest = Color(0xFFE2DFE7)
val M3ExpLightOutline = Color(0xFF757680)
val M3ExpLightOutlineVariant = Color(0xFFC6C6D0)

// M3 Expressive 深色表面层级 (Obsidian Velvet Layers)
//
// 旧阶梯 background=0x121318 / containerLow=0x1A1B21 / container=0x1E1F25 / high=0x282A30，
// 相邻层只差 4–8 个亮度单位：卡片、列表行、弹层在手机上几乎贴平，整屏糊成一片灰；
// 而深色下阴影本来就近乎无效，等于没有任何层次手段。这里把阶梯拉开到 8–12 个亮度单位，
// 并把 outlineVariant 提亮到能画出 1dp 发丝边的程度（0x3A3B45）。
val M3ExpDarkBackground = Color(0xFF0E0F14)
val M3ExpDarkOnBackground = Color(0xFFE3E2E9)
val M3ExpDarkSurface = Color(0xFF0E0F14)
val M3ExpDarkOnSurface = Color(0xFFE3E2E9)
val M3ExpDarkSurfaceVariant = Color(0xFF45464F)
val M3ExpDarkOnSurfaceVariant = Color(0xFFC6C6D0)
val M3ExpDarkSurfaceContainerLowest = Color(0xFF08090D)
val M3ExpDarkSurfaceContainerLow = Color(0xFF15161C)
val M3ExpDarkSurfaceContainer = Color(0xFF1A1B22)
val M3ExpDarkSurfaceContainerHigh = Color(0xFF23242C)
val M3ExpDarkSurfaceContainerHighest = Color(0xFF2D2E37)
val M3ExpDarkOutline = Color(0xFF90909A)
val M3ExpDarkOutlineVariant = Color(0xFF3A3B45)

// ======================= 强调与叠层（Accent & Scrim Tokens） =======================
//
// 全仓 561 处硬编码色值，其中大量是「品牌感」的临时涂色：渐变、悬浮层的半透明底、
// 选中态的淡色面。它们散落在各 feature 里，切主题时不会跟着变。这里把它们收成 token：
// 一屏只允许出现一次强调渐变（hero / 空状态 / 选中卡），其余区域靠表面阶梯说话。

/** 品牌强调渐变（浅色）：主色 → 三级色，用于 hero 与空状态。 */
val TianyanAccentGradientLight = listOf(Color(0xFF4259C3), Color(0xFF8666AE))

/** 品牌强调渐变（深色）：提亮后的主色 → 三级色，避免深底上发灰。 */
val TianyanAccentGradientDark = listOf(Color(0xFFBAC3FF), Color(0xFFD3BBFF))

/** 悬浮层压暗（对话框背后的 scrim），比纯黑更贴主题。 */
val TianyanScrim = Color(0x99000000)

/** 浅色下的发丝边（1dp 描边），比 outlineVariant 更淡，避免卡片显脏。 */
val TianyanHairlineLight = Color(0x1F1A1B21)

/** 深色下的发丝边：亮线而非暗线，卡片才能在深底上「浮」起来。 */
val TianyanHairlineDark = Color(0x1FFFFFFF)
