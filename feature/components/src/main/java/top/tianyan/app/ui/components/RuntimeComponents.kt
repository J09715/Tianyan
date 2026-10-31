@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package top.tianyan.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.components.R
import androidx.compose.ui.res.stringResource

/**
 * 天衍 (Tianyan) 四大核心中枢导航定义：
 * 天衍（开辟画布）· 智枢（AI 结对）· 工坊（工作区）· 乾坤（设置与模型）
 */
enum class MainDestination(val labelRes: Int, val subtitleRes: Int, val icon: RuntimeIconName) {
    Home(R.string.components_nav_home, R.string.components_nav_subtitle_home, RuntimeIconName.NavDashboard),
    Agent(R.string.components_nav_agent, R.string.components_nav_subtitle_agent, RuntimeIconName.NavMessage), Bots(R.string.components_nav_bots, R.string.components_nav_subtitle_bots, RuntimeIconName.Bot),
    Workspace(R.string.components_nav_workspace, R.string.components_nav_subtitle_workspace, RuntimeIconName.NavRepository),
    Settings(R.string.components_nav_settings, R.string.components_nav_subtitle_settings, RuntimeIconName.NavSettings),
}

/**
 * 天衍 · 核心底部中枢导航栏（Material 3 NavigationBar）。
 */
@Composable
fun RuntimeBottomBar(
    selected: MainDestination,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        MainDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNavigate(destination)
                },
                icon = { RuntimeIcon(destination.icon, Modifier.size(24.dp)) },
                label = {
                    Text(
                        text = stringResource(destination.labelRes),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    statusText: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val openNavigation = LocalAppNavigation.current
    Box(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onBack == null) TianyanBrandBadge(30.dp)
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (statusText != null) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    RuntimeIconButton(onClick = onBack, contentDescription = stringResource(R.string.components_back)) {
                        RuntimeIcon(RuntimeIconName.Back, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                } else if (openNavigation != null) RuntimeIconButton(onClick = openNavigation, contentDescription = stringResource(R.string.components_open_navigation)) {
                    RuntimeIcon(RuntimeIconName.Menu, Modifier.size(24.dp))
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/**
 * 天衍品牌标志徽章
 */
@Composable
fun TianyanBrandBadge(size: Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(Color.White)
            .padding(size * 0.10f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.components_tianyan_logo),
            contentDescription = stringResource(R.string.components_tianyan_logo),
            modifier = Modifier.size(size * 0.80f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 天衍精制卡片组件：支持自适应表面色与细腻描边
 */
@Composable
fun RuntimeCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = Color.Transparent,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = borderColor.takeIf { it.alpha > 0f }?.let { BorderStroke(1.dp, it) }

    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun RuntimeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    thumbContent: (@Composable (() -> Unit))? = null,
) {
    androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors, thumbContent = thumbContent)
}

@Composable
fun RuntimeLinearProgressIndicator(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    wavy: Boolean = false,
) {
    // 无确定进度即走 Expressive 波浪；确定进度默认回落经典样式，wavy 显式开启波浪
    if (progress == null) androidx.compose.material3.LinearWavyProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
    else if (wavy) androidx.compose.material3.LinearWavyProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
    else androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
}

@Composable
fun RuntimeCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
    strokeWidth: Dp = 4.5.dp,
) {
    // 默认参数走 Expressive 形变加载指示器；显式描边/轨道参数保留经典圆环（小尺寸定制场景）
    if (strokeWidth == 4.5.dp && trackColor == Color.Transparent) androidx.compose.material3.LoadingIndicator(modifier = modifier, color = color)
    else androidx.compose.material3.CircularProgressIndicator(modifier = modifier, color = color, trackColor = trackColor, strokeWidth = strokeWidth)
}

@Composable
fun RuntimeCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors)
}

@Composable
fun RuntimeRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
) {
    androidx.compose.material3.RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled, colors = colors)
}

@Composable
fun RuntimeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
    )
}

@Composable
fun RuntimeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        icon = icon,
    )
}


/**
 * 呼吸状态指示药丸
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val alphaAnim = if (pulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        ).value
    } else 1f

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alphaAnim)),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun IconTile(
    icon: RuntimeIconName,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 42.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        RuntimeIcon(icon, Modifier.size(size * 0.48f), tint = color)
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isCode: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.62f, fill = false),
        )
    }
}

@Composable
fun CodeBlockRow(
    label: String,
    code: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val shape = RoundedCornerShape(8.dp)
        val codeModifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
        Box(modifier = codeModifier) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun EmptyPanel(
    icon: RuntimeIconName,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconTile(icon, color = MaterialTheme.colorScheme.primary, size = 48.dp)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val bannerModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(color.copy(alpha = 0.08f))
        .border(1.dp, color.copy(alpha = 0.25f), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Row(
        modifier = bannerModifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuntimeIcon(if (isError) RuntimeIconName.Alert else RuntimeIconName.Check, Modifier.size(17.dp), color)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            val dismissLabel = stringResource(R.string.components_close)
            RuntimeIconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp),
                contentDescription = dismissLabel,
            ) {
                RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 🌟 滚动列表平滑渐隐遮罩 (Scroll Fading Edge Mask)
 * 为列表顶部和底部提供平滑的 Alpha 渐隐过渡，消除与 TopBar / BottomBar 之间的生硬切边。
 *
 * ⚠️ 性能提示（2026-09-14）：本实现依赖 `CompositingStrategy.Offscreen` ——
 * `BlendMode.DstIn` 要求先渲染到独立离屏缓冲才能做遮罩。挂在 LazyColumn 上时，
 * **滚动期间每帧都要全量离屏合成**，屏幕越大代价越高，表现为「静止不卡、快速滑动明显掉帧」。
 * 消息列表已改用 [ScrollFadeOverlay]（列表外叠加渐变，代价近乎为零）。
 * 仅在「非滚动、或面积很小」的场景继续使用本修饰符。
 */
fun Modifier.scrollFadingEdge(
    top: Dp = 14.dp,
    bottom: Dp = 14.dp,
): Modifier = this.graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val topPx = top.toPx()
    val bottomPx = bottom.toPx()
    val h = size.height

    if (topPx > 0f && h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = topPx.coerceAtMost(h),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
    if (bottomPx > 0f && h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = (h - bottomPx).coerceAtLeast(0f),
                endY = h,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

/**
 * 🌟 滚动渐隐遮罩（轻量版）：绘制为**叠加在滚动内容之上**的两段渐变，不使用离屏合成。
 *
 * 与 [scrollFadingEdge] 的取舍：
 *  - scrollFadingEdge 用 DstIn 真正「擦除」内容 alpha（效果更干净），
 *    但要求 Offscreen 合成 → 滚动每帧全量离屏渲染，是滑动卡顿的头号来源。
 *  - 本实现改为「在内容上方叠一层从背景色到透明的渐变」，视觉近似（顶/底自然淡出），
 *    但**零离屏合成开销**，适合挂在长列表这类滚动频繁、面积大的场景。
 *
 * 用法：把滚动内容与本 Overlay 放进同一个 Box，Overlay 位于内容之上：
 * ```
 * Box {
 *     LazyColumn(...)
 *     ScrollFadeOverlay(top = 4.dp, bottom = 20.dp)
 * }
 * ```
 */
@Composable
fun ScrollFadeOverlay(
    modifier: Modifier = Modifier,
    top: Dp = 14.dp,
    bottom: Dp = 14.dp,
    color: Color = MaterialTheme.colorScheme.surface,
) {
    Box(modifier.fillMaxSize()) {
        if (top > 0.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(top)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(listOf(color, color.copy(alpha = 0f))),
                    ),
            )
        }
        if (bottom > 0.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(bottom)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(color.copy(alpha = 0f), color)),
                    ),
            )
        }
    }
}

/**
 * 悬浮操作按钮 (M3 Floating Action Button)：尺寸 56dp，形状 RoundedCornerShape(16.dp)，
 * 默认 primaryContainer / onPrimaryContainer 配色与 3dp 色调海拔。
 */
@Composable
fun RuntimeFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = 3.dp,
        pressedElevation = 6.dp,
    ),
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        content = content,
    )
}

/**
 * 主题自适应小型悬浮操作按钮 (M3 Small Floating Action Button)：
 * 遵循 M3 规范：尺寸 40dp，形状 RoundedCornerShape(12.dp)。
 */
@Composable
fun RuntimeSmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = 3.dp,
        pressedElevation = 6.dp,
    ),
    content: @Composable () -> Unit,
) {
    RuntimeFloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        content = content,
    )
}

/**
 * 主题自适应过滤标签 (M3 Filter Chip)：
 * - 遵循 M3 规范：高度 32dp，形状 RoundedCornerShape(8.dp)，文字 labelMedium；
 * - 包含 minimumInteractiveComponentSize() 满足 48dp 触控标准；
 */
@Composable
fun RuntimeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

