package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.AgentQuestion
import top.tianyan.app.core.model.AgentQuestionAnswer
import top.tianyan.app.core.model.AgentQuestionRecommendation
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCheckbox
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeRadioButton
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * 智能体提问卡片。
 *
 * 交互对齐 DeepSeek Harness 的 question 卡片，但按移动端收敛：
 *  · 占据输入框位置（一次只呈现一题），而不是弹窗遮住对话；
 *  · 多选题自由文本是对选项的补充，单选则与选项互斥；
 *  · 推荐项仅在标签末尾带「(Recommended)」时标记，且**回传原始标签**；
 *  · 带「跳过」——模型收到全跳过时会被告知用户未确认，不得当作同意。
 */
@Composable
internal fun AgentQuestionCard(
    questions: List<AgentQuestion>,
    onAnswer: (List<AgentQuestionAnswer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (questions.isEmpty()) return

    // 每题独立保存草稿，翻页回来不丢；多题时逐题作答后汇总提交。
    val drafts = remember(questions) { mutableStateMapOf<String, AgentQuestionAnswer>() }
    var index by remember(questions) { mutableStateOf(0) }
    val current = questions[index.coerceIn(0, questions.lastIndex)]
    val draft = drafts[current.id] ?: AgentQuestionAnswer(id = current.id)

    fun update(answer: AgentQuestionAnswer) {
        drafts[current.id] = answer
    }

    fun answered(question: AgentQuestion, answer: AgentQuestionAnswer?): Boolean =
        answer != null && (answer.selected.isNotEmpty() || answer.custom.trim().isNotEmpty())

    val isLast = index >= questions.lastIndex
    val allAnswered = questions.all { answered(it, drafts[it.id]) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 标题行：短标题 + 进度
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Prompt,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = current.header ?: "智能体提问",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (questions.size > 1) {
                    Text(
                        text = "${index + 1}/${questions.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 问题正文 + 选项：内容可能超出卡片高度，按 UI 铁律给滚动容器
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = current.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                current.options.forEachIndexed { optionIndex, option ->
                    val parsed = AgentQuestionRecommendation.parse(option.label)
                    val selected = option.label in draft.selected
                    QuestionOptionRow(
                        label = parsed.displayLabel,
                        description = option.description,
                        recommended = parsed.recommended,
                        selected = selected,
                        multiSelect = current.multiSelect,
                        onClick = {
                            val next = if (current.multiSelect) {
                                val set = draft.selected.toMutableList()
                                if (selected) set.remove(option.label) else set.add(option.label)
                                draft.copy(id = current.id, selected = set, skipped = false)
                            } else {
                                draft.copy(id = current.id, selected = listOf(option.label), skipped = false)
                            }
                            update(next)
                        },
                    )
                }

                // 自由文本作答：始终提供，单选时与选项互斥（normalized 负责收口）
                QuestionCustomInput(
                    value = draft.custom,
                    placeholder = if (current.options.isEmpty()) "输入你的回答" else "或输入其它回答",
                    onValueChange = { text ->
                        update(draft.copy(id = current.id, custom = text, skipped = false))
                    },
                )
            }

            // 操作行：多题时翻页；最后一题才提交
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeTextButton(
                    onClick = {
                        update(AgentQuestionAnswer(id = current.id, skipped = true))
                        if (isLast) {
                            onAnswer(questions.map { drafts[it.id] ?: AgentQuestionAnswer(id = it.id, skipped = true) })
                        } else {
                            index += 1
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("跳过", style = MaterialTheme.typography.labelLarge)
                }

                Box(Modifier.weight(1f))

                if (index > 0) {
                    RuntimeTextButton(
                        onClick = { index -= 1 },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("上一题", style = MaterialTheme.typography.labelLarge)
                    }
                }

                RuntimeButton(
                    onClick = {
                        if (isLast) {
                            onAnswer(questions.map { drafts[it.id] ?: AgentQuestionAnswer(id = it.id, skipped = true) })
                        } else {
                            index += 1
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (isLast) "提交" else "下一题",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            // 提交前置条件提示：最后一题且完全没作答时说明可跳过
            if (isLast && !allAnswered) {
                Text(
                    text = "未作答的题目会记为「跳过」，智能体不会视为已确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(
    label: String,
    description: String?,
    recommended: Boolean,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 单选用 RadioButton、多选用 Checkbox，与选项语义一致
            if (multiSelect) {
                RuntimeCheckbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                RuntimeRadioButton(selected = selected, onClick = onClick)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (recommended) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ) {
                            Text(
                                text = "推荐",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCustomInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 3,
            )
        }
    }
}
