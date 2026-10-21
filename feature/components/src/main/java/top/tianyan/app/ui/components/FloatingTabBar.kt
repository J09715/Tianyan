package top.tianyan.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.theme.Radius
import top.tianyan.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底部悬浮液态玻璃 Tab 栏 —— 移植自 AiCode core/ui/FloatingTabBar.kt（选中索引版）。
 * 指示器弹簧跟手、长按可拖、滚动时整条弱化。图标体系换成天衍 RuntimeIcon。
 */

/**
 * Git 泳道/状态配色。
 *
 * ## 为什么只保留色板，不保留间距与圆角
 *
 * 原来这里还有 `spacingXs/sm/md/lg/xl` 与 `radiusXs/sm/md/lg/pill` 五组尺寸，
 * 与全仓的 [Spacing] / [Radius] 令牌是同一件事的两套写法，且数值不一致
 * （这里 radiusMd=10dp，令牌里 md=16dp）。两套并存的必然结果是同屏里出现
 * 两种圆角。尺寸类已全部并入 [Spacing] / [Radius]，本对象只留下**语义色**——
 * 也就是那些确实不是「间距/圆角」而是**领域配色**的部分。
 *
 * 泳道色是 IDE 式的分类色（蓝/绿/琥珀/紫/青/红/粉/金），按列循环。它与语义色的
 * success/warning 不同：那些表达状态，泳道色表达「哪一条分支」，不能互换。
 */
object GitTokens {
    /** 泳道调色板（IDE 风格，按列循环）。 */
    val laneColors = listOf(
        Color(0xFF2563EB), // 经典蓝
        Color(0xFF16A34A), // 翠绿
        Color(0xFFF59E0B), // 琥珀
        Color(0xFF8B5CF6), // 优雅紫
        Color(0xFF06B6D4), // 青蓝
        Color(0xFFEF4444), // 珊瑚红
        Color(0xFFEC4899), // 亮粉
        Color(0xFFEAB308), // 柠檬金
    )

    /** Git 状态徽章统一色彩。 */
    val statusAdded = Color(0xFF16A34A)
    val statusModified = Color(0xFFD97706)
    val statusDeleted = Color(0xFFDC2626)
    val statusRenamed = Color(0xFF2563EB)
    val statusUntracked = Color(0xFF94A3B8)
    val statusConflict = Color(0xFF9333EA)
    val statusTypeChanged = Color(0xFF0891B2)
    val statusDefault = Color(0xFF64748B)
}

/**
 * Git 面板的卡片底色。
 *
 * 原先按背景亮度手选（浅色用纯白、深色用 surfaceContainerHigh），但浅色主题下
 * 纯白卡片压在浅灰页面上看不出边界。这里统一交给 M3 的 surfaceContainerHigh：
 * 它在两个主题里都有明确的层次关系，不需要按亮度分支。
 */
@Composable
fun gitCardSurface(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** Git 面板的弱化底色（行 hover、分组条底）。 */
@Composable
fun gitMutedSurface(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

@Composable
fun gitSubtleBorder(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

@Composable
fun gitSubtleText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 底部悬浮 tab 栏的单个 tab 项：图标 + 文案。 */
data class FloatingTabItem(
    val icon: RuntimeIconName,
    val label: String,
)

/**
 * 底部悬浮液态玻璃 Tab 栏（基础选中索引版，移植 AiCode）：
 * 玻璃胶囊 + 弹簧指示器 + 长按拖拽 + 滚动弱化（isScrolling → 35% 透明）。
 */
@Composable
fun FloatingTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    items: List<FloatingTabItem>,
    maskColor: Color,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.35f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabbar-content-alpha",
    )

    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val surfaceVariantColor = colorScheme.surfaceVariant
    val outlineVariantColor = colorScheme.outlineVariant
    val primaryColor = colorScheme.primary
    val primaryContainerColor = colorScheme.primaryContainer
    val cardSurfaceColor = gitCardSurface()
    val mutedSurfaceColor = gitMutedSurface()
    val pageBackgroundColor = colorScheme.background

    val glassBackgroundBrush = remember(isLight, surfaceColor, surfaceVariantColor, cardSurfaceColor, mutedSurfaceColor) {
        if (isLight) {
            Brush.verticalGradient(listOf(cardSurfaceColor.copy(alpha = 0.94f), mutedSurfaceColor.copy(alpha = 0.90f)))
        } else {
            Brush.verticalGradient(listOf(surfaceVariantColor.copy(alpha = 0.92f), surfaceColor.copy(alpha = 0.88f)))
        }
    }

    val glassBorderBrush = remember(isLight, outlineVariantColor, pageBackgroundColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.14f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    outlineVariantColor.copy(alpha = 0.55f),
                    outlineVariantColor.copy(alpha = 0.20f),
                    pageBackgroundColor.copy(alpha = 0.60f),
                ),
            )
        }
    }

    val tabBounds = remember { mutableStateMapOf<Int, Rect>() }
    val currentSelected by rememberUpdatedState(selected)
    val density = LocalDensity.current

    var dragX by remember { mutableFloatStateOf(Float.NaN) }
    val isDragging = !dragX.isNaN()

    val indicatorTarget = if (isDragging) dragX else tabBounds[selected]?.left ?: 0f

    val indicatorX by animateFloatAsState(
        targetValue = indicatorTarget,
        animationSpec = if (isDragging) tween(0) else spring(dampingRatio = 0.76f, stiffness = 380f),
        label = "indicator-x",
    )

    val stretchScale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        stretchScale.snapTo(1.10f)
        stretchScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(
                Brush.verticalGradient(listOf(maskColor.copy(alpha = 0f), maskColor.copy(alpha = 0.98f))),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = Spacing.sm)
                .graphicsLayer { alpha = contentAlpha }
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(percent = 50),
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color(0xFF040A14).copy(alpha = 0.35f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color(0xFF040A14).copy(alpha = 0.50f),
                )
                .clip(RoundedCornerShape(percent = 50))
                .background(glassBackgroundBrush)
                .border(1.dp, glassBorderBrush, RoundedCornerShape(percent = 50))
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                .pointerInput(items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val w = tabBounds.values.firstOrNull()?.width ?: 0f
                            dragX = start.x - w / 2f
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = change.position.x
                            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f
                            if (tabWidth > 0f) {
                                val minX = tabBounds.values.minOfOrNull { it.left } ?: 0f
                                val maxX = (tabBounds.values.maxOfOrNull { it.right } ?: tabWidth) - tabWidth
                                dragX = (x - tabWidth / 2f).coerceIn(minX, maxX)
                            }
                            val target = items.indices.minByOrNull { abs((tabBounds[it]?.center?.x ?: x) - x) }
                            if (target != null && target != currentSelected) onSelect(target)
                        },
                        onDragEnd = { dragX = Float.NaN },
                        onDragCancel = { dragX = Float.NaN },
                    )
                },
        ) {
            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f

            if (tabWidth > 0f) {
                val indicatorWidthDp = with(density) { tabWidth.toDp() }
                val primaryColor = MaterialTheme.colorScheme.primaryContainer

                val indicatorBrush = remember(isLight, primaryColor) {
                    if (isLight) {
                        Brush.verticalGradient(listOf(primaryColor.copy(alpha = 0.95f), primaryColor.copy(alpha = 0.85f)))
                    } else {
                        Brush.verticalGradient(listOf(primaryColor.copy(alpha = 0.90f), primaryColor.copy(alpha = 0.72f)))
                    }
                }

                val indicatorBorderBrush = remember(isLight, primaryContainerColor) {
                    Brush.verticalGradient(
                        listOf(
                            if (isLight) Color.Black.copy(alpha = 0.08f) else primaryContainerColor.copy(alpha = 0.35f),
                            if (isLight) Color.Black.copy(alpha = 0.02f) else primaryContainerColor.copy(alpha = 0.08f),
                        ),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(indicatorX.roundToInt(), 0) }
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.05f else stretchScale.value
                            scaleY = if (isDragging) 0.98f else (2f - stretchScale.value).coerceIn(0.95f, 1f)
                        }
                        .width(indicatorWidthDp)
                        .height(44.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(percent = 50),
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.08f),
                        )
                        .clip(RoundedCornerShape(percent = 50))
                        .background(indicatorBrush)
                        .border(1.dp, indicatorBorderBrush, RoundedCornerShape(percent = 50)),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val tabScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "tab-press-scale",
                    )

                    val fgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tab-fg-color",
                    )

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab-icon-scale",
                    )

                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { tabBounds[index] = it.boundsInParent() }
                            .widthIn(min = 84.dp)
                            .graphicsLayer {
                                scaleX = tabScale
                                scaleY = tabScale
                            }
                            .clip(Radius.mdShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(index) },
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        RuntimeIcon(
                            name = item.icon,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            tint = fgColor,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = fgColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
