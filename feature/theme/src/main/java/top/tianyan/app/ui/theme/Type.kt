package top.tianyan.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppSans = FontFamily.SansSerif

val AppTypography = Typography(
    // displayLarge / displayMedium 此前未覆盖，沿用 Material 默认（57/45sp）。
    // 默认值与本仓库已定制的标题阶梯不同源：displaySmall 被收紧到 38sp 且
    // letterSpacing -1.1，若上两档仍是 57sp 且字距宽松，同一页里出现两套性格。
    // 这里按「字号每增大一档，字距再收紧约 0.2sp」的既有规律补齐。
    displayLarge = Typography().displayLarge.copy(
        fontFamily = AppSans,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = Typography().displayMedium.copy(
        fontFamily = AppSans,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.3).sp,
    ),
    displaySmall = Typography().displaySmall.copy(
        fontFamily = AppSans,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.1).sp,
    ),
    headlineLarge = Typography().headlineLarge.copy(
        fontFamily = AppSans,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontFamily = AppSans,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.45).sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontFamily = AppSans,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = AppSans,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = AppSans,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = Typography().titleSmall.copy(
        fontFamily = AppSans,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = Typography().bodyLarge.copy(
        fontFamily = AppSans,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        fontFamily = AppSans,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = Typography().bodySmall.copy(
        fontFamily = AppSans,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = AppSans,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontFamily = AppSans,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = Typography().labelSmall.copy(
        fontFamily = AppSans,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp,
    ),
)
