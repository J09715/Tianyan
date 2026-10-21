// language: Kotlin, module: feature/components, ui: Compose Material 3
package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.theme.Spacing
import top.tianyan.app.ui.theme.TianyanTheme

/**
 * 「无内容」状态（Empty State）单一入口。
 *
 * ## 这个文件解决什么
 *
 * 引入前全仓有 131 处自写的 `isEmpty()` 分支，渲染形态各不相同：有的只放一行灰字，
 * 有的塞了整块 `Column` 手工居中，有的干脆什么都不画——用户看到白屏，以为是卡死。
 * 空态是**产品语言**，不是兜底：它得说清「这里本该有什么」和「怎么让它有」。
 *
 * ## 三件事必须齐
 *
 * 1. **图标** —— 不是装饰，是让人在 0.2 秒内认出这是哪一类内容。
 * 2. **标题** —— 一句话说清状态（「还没有工作区」而不是「无数据」）。
 * 3. **出路** —— 有可执行动作就给按钮；确实无事可做才留空（例如网络类故障走
 *    [RuntimeErrorState]，不要在这里给「重试」，语义不同）。
 *
 * 文案要求：标题 12 字以内、描述 40 字以内，不写「请」「您」。描述里解释**为什么空**，
 * 不解释**空态是什么**。
 *
 * ## 可选密度
 *
 * 整屏空态用 [RuntimeEmptyState]（垂直居中、留白足）；卡片或分区内的空态用
 * [RuntimeEmptyStateInline]（横排、单行高度），避免在小容器里撑出大片空白。
 */
enum class StateTone {
    /** 中性灰 —— 纯粹「还没有东西」，无褒贬。 */
    Neutral,

    /** 主题色 —— 引导性空态（该去创建点什么）。 */
    Info,

    /** 绿 —— 正常收尾（任务清空、全部已读）。 */
    Success,

    /** 琥珀 —— 需要注意（权限缺失、配置未完成）。 */
    Warning,

    /** 红 —— 出错（真正的失败请走 RuntimeErrorState）。 */
    Danger,
}

/** 语气到语义色的映射。颜色一律取自设计令牌，不写字面色值。 */
@Composable
internal fun StateTone.color(): Color = when (this) {
    StateTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    StateTone.Info -> TianyanTheme.colors.info
    StateTone.Success -> TianyanTheme.colors.success
    StateTone.Warning -> TianyanTheme.colors.warning
    StateTone.Danger -> TianyanTheme.colors.danger
}

/**
 * 整屏空态。
 *
 * @param fillHeight 是否撑满父容器并垂直居中。列表在可滚动容器内时传 false，
 *   否则空态会被滚出视口。
 * @param primaryActionLabel 有出路时给按钮文案；与 [onPrimaryAction] 必须成对出现，
 *   只给一半会在编译期就被下面那条 `require` 拦下——空态给了按钮却没动作是纯 bug。
 */
@Composable
fun RuntimeEmptyState(
    icon: RuntimeIconName,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    tone: StateTone = StateTone.Neutral,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    fillHeight: Boolean = true,
) {
    require((primaryActionLabel == null) == (onPrimaryAction == null)) {
        "RuntimeEmptyState: primaryActionLabel 与 onPrimaryAction 必须成对提供"
    }
    require((secondaryActionLabel == null) == (onSecondaryAction == null)) {
        "RuntimeEmptyState: secondaryActionLabel 与 onSecondaryAction 必须成对提供"
    }
    val accent = tone.color()
    val container = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()

    Box(
        modifier = container.padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // 宽屏上限：正文超过 360dp 行长会难读，空态尤其明显
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconTile(icon = icon, color = accent, size = 56.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                RuntimeButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.padding(top = Spacing.xs),
                    tonal = secondaryActionLabel != null,
                ) {
                    Text(primaryActionLabel)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                RuntimeTextButton(onClick = onSecondaryAction) {
                    Text(
                        text = secondaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 行内空态：横排、单行高，塞进 `RuntimeCard` 或列表尾部而不破坏节奏。
 *
 * 只给一个可选动作——行内空间不够放两个按钮，需要两个就说明该用整屏空态。
 */
@Composable
fun RuntimeEmptyStateInline(
    icon: RuntimeIconName,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    tone: StateTone = StateTone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    require((actionLabel == null) == (onAction == null)) {
        "RuntimeEmptyStateInline: actionLabel 与 onAction 必须成对提供"
    }
    val accent = tone.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon = icon, color = accent, size = 36.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            RuntimeTextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
        }
    }
}

/**
 * 整屏加载态：居中图标 + 文案 + 可选进度。
 *
 * 只在**首帧且无任何旧数据**时用。已有数据时不要整屏盖住——用户会丢失上下文，
 * 改用行内 spinner 或骨架屏（[SkeletonList] 等）。
 *
 * @param progress 0f..1f 时显示确定进度条；null 显示不定进度（转圈）。
 */
@Composable
fun RuntimeLoadingState(
    message: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    icon: RuntimeIconName = RuntimeIconName.Activity,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconTile(icon = icon, color = MaterialTheme.colorScheme.primary, size = 48.dp)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress != null) {
                RuntimeLinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RuntimeExpressive.LoadingIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                )
            }
        }
    }
}
