package top.tianyan.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

/** 全圆角药丸形状 (Capsule Shape) */
val CapsuleShape: Shape = RoundedCornerShape(percent = 50)

private val SegmentSpringSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)

// ═══════════════════════════════════════════════════════════════════════════
// 1. RuntimeSegmentedControl（药丸分段选择器）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 药丸分段选择器 (Segmented Control)：Material 3 表面 + 弹簧滑块指示。
 */
@Composable
fun <T> RuntimeSegmentedControl(
    items: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemContent: @Composable (item: T, isSelected: Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .clip(CapsuleShape)
            .height(38.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = CapsuleShape,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(3.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val totalWidth = constraints.maxWidth.toFloat()
            val segmentWidth = totalWidth / items.size
            val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

            val animatedIndex by animateFloatAsState(
                targetValue = selectedIndex.toFloat().coerceIn(0f, (items.size - 1).toFloat()),
                animationSpec = SegmentSpringSpec,
                label = "M3SegmentedOffset",
            )

            // M3 实体滑块
            Surface(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = if (isLtr) animatedIndex * segmentWidth else -animatedIndex * segmentWidth
                    }
                    .width(with(LocalDensity.current) { segmentWidth.toDp() })
                    .height(32.dp),
                shape = CapsuleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {}

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CapsuleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled,
                                role = Role.Tab,
                            ) {
                                if (index != selectedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(index)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        itemContent(item, isSelected)
                    }
                }
            }
        }
    }
}

/**
 * 字符型选项分段选择器重载（最常见的文本分段）。
 */
@Composable
fun RuntimeSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RuntimeSegmentedControl(
        items = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
    ) { text, isSelected ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
            ),
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. RuntimeOutlinedTextField & RuntimeTextField（文本输入框）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 描边文本输入框，委托 Material 3 OutlinedTextField。
 */
@Composable
fun RuntimeOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = actualInteractionSource,
        shape = shape,
        colors = colors,
    )
}

/**
 * 填充型文本输入框别名，与 RuntimeOutlinedTextField 共享同一实现。
 */
@Composable
fun RuntimeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    RuntimeOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. RuntimeModalBottomSheet（模态底部抽屉）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 模态底部抽屉，委托 Material 3 ModalBottomSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = if (containerColor != Color.Unspecified) containerColor else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle ?: { BottomSheetDefaults.DragHandle() },
        content = content,
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 4. RuntimeDropdownMenu & RuntimeDropdownMenuItem（上下文菜单）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 上下文菜单，委托 Material 3 DropdownMenu。
 */
@Composable
fun RuntimeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
        shape = shape,
        content = content,
    )
}

/**
 * 天衍 · 上下文菜单项：圆角按压高亮。
 */
@Composable
fun RuntimeDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by actualInteractionSource.collectIsPressedAsState()
    val isDark = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f

    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPressed) {
                    if (isDark) Color.White.copy(alpha = 0.12f)
                    else Color.Black.copy(alpha = 0.08f)
                } else Color.Transparent,
            ),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = actualInteractionSource,
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 5. RuntimeSearchBar（胶囊搜索栏）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 胶囊搜索栏：左侧搜索图标、BasicTextField 与渐入渐出的一键清空按钮。
 */
@Composable
fun RuntimeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索...",
    leadingIcon: (@Composable () -> Unit)? = null,
    onSearch: ((String) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    RuntimeFilledTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = leadingIcon ?: { RuntimeIcon(RuntimeIconName.Search) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                RuntimeIconButton(
                    onClick = { onQueryChange(""); onClear?.invoke() },
                    contentDescription = androidx.compose.ui.res.stringResource(top.tianyan.app.feature.components.R.string.components_clear_search),
                ) { RuntimeIcon(RuntimeIconName.Close) }
            }
        } else null,
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke(query) }),
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 6. RuntimeBadge（状态/计数角标）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · 状态/计数角标，委托 Material 3 Badge。
 */
@Composable
fun RuntimeBadge(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = MaterialTheme.colorScheme.onError,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    Badge(
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content,
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 7. RuntimeSheetHeader（底部抽屉标准顶栏）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 天衍 · iOS 规范底部抽屉标准顶栏 (RuntimeSheetHeader)。
 *
 * 遵循 iOS UISheetPresentationController 顶栏规范：
 * - 左侧：取消/关闭动作文本；
 * - 中央：标题文本（加粗 Headline 居中）；
 * - 右侧：完成/确认动作高光文本；
 * - 下边缘：极细微高光反光分割线。
 */
@Composable
fun RuntimeSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirm: (() -> Unit)? = null,
    confirmText: String = "完成",
    dismissText: String = "取消",
) {
    val isDark = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧取消
            Text(
                text = dismissText,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CapsuleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            // 中央标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 右侧确认/完成
            if (onConfirm != null) {
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .clickable(role = Role.Button, onClick = onConfirm)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }

        // 底部 1px 细微光影分割线
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}
