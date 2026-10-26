package top.tianyan.app.ui.theme

import androidx.compose.material3.Typography

/**
 * M3 规范字阶（系统默认字体栈，Roboto / 设备 SansSerif）。
 *
 * 原实现把 Display / Headline / Title 全量改为 Bold / SemiBold 并压缩字距，偏离 M3 规范。
 * 这里回归 Material 3 默认字重体系（大标题 400、label 500），层级由规范字级与字重共同承载。
 */
val AppTypography = Typography()
