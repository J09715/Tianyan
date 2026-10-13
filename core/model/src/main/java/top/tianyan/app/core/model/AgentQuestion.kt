package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 智能体向用户提问（ask_user_question）的模型层。
 *
 * 设计对齐 DeepSeek Harness 的 `ask_user_question` 工具：
 *  · 一次调用可携带多个问题（`questions` 数组），逐题作答、随时可跳过；
 *  · 每题可给候选选项，也可自由文本作答；
 *  · 选项标签若带「(Recommended)」后缀，UI 解析出来做推荐标记，
 *    但**作答必须回传原始标签**（含后缀），否则模型对不上自己给的选项；
 *  · 单选的自由文本与选项互斥；多选的自由文本是对选项的补充，两者并存。
 *
 * 本文件刻意保持 Pure Kotlin（不引用 android / androidx / Compose），
 * 供 harness 与 feature 层共用。
 */

/** 单题的候选项。 */
@Serializable
data class AgentQuestionOption(
    /** 展示与回传都用的标签。推荐项在末尾带 "(Recommended)"。 */
    val label: String,
    /** 可选补充说明，展示在标签下方。 */
    val description: String? = null,
)

/** 智能体提出的单个问题。 */
@Serializable
data class AgentQuestion(
    /** 稳定标识，作答结果按它与问题对应。 */
    val id: String,
    /** 问题正文。 */
    val question: String,
    /** 可选短标题，如「确认」「选择模式」。 */
    val header: String? = null,
    /** 候选选项；为空表示纯自由文本作答。 */
    val options: List<AgentQuestionOption> = emptyList(),
    /** 是否多选。默认单选。 */
    val multiSelect: Boolean = false,
)

/** 单题作答结果。 */
@Serializable
data class AgentQuestionAnswer(
    val id: String,
    /** 选中的选项标签（原样回传，含 "(Recommended)" 后缀）。 */
    val selected: List<String> = emptyList(),
    /** 自由文本作答。单选时与 [selected] 互斥；多选时为补充项。 */
    val custom: String = "",
    /** 用户跳过了该题。 */
    val skipped: Boolean = false,
)

/** 一次提问的完整作答。 */
@Serializable
data class AgentQuestionResponse(
    val answers: List<AgentQuestionAnswer> = emptyList(),
)

/**
 * 推荐标记解析：从选项标签末尾剥离 "(Recommended)" / "（推荐）"。
 *
 * [displayLabel] 用于界面展示，[rawLabel] 必须原样回传给模型——
 * 模型是按自己给出的原始标签来理解作答的。
 */
object AgentQuestionRecommendation {
    private val SUFFIX = Regex("""\s*(?:\((?:recommended|推荐)\)|（(?:recommended|推荐)）)\s*$""", RegexOption.IGNORE_CASE)

    data class Parsed(val displayLabel: String, val recommended: Boolean)

    fun parse(label: String): Parsed {
        val match = SUFFIX.find(label) ?: return Parsed(label, false)
        return Parsed(label.removeRange(match.range).trimEnd(), true)
    }

    /** 只有排在第一位的推荐项才算「默认推荐」。 */
    fun isLeadingRecommendation(index: Int, label: String): Boolean = index == 0 && parse(label).recommended
}

/**
 * 作答是否有效：选中了选项，或填写了非空白自由文本。
 * 对齐 Harness：`selected.length > 0 || custom.trim() !== ""`。
 */
fun AgentQuestionAnswer.isAnswered(): Boolean = selected.isNotEmpty() || custom.trim().isNotEmpty()

/**
 * 合并同一题的作答草稿为最终回传值。
 *
 * 单选：自由文本与选项互斥——填了自由文本就只回 custom（自定义是排他的）；
 * 多选：两者并存，自由文本作为对所选选项的补充。
 * 与 Harness 的编码规则一致：
 *   selected = (custom == "" || multiSelect) ? 已选 : []
 *   custom   = custom == "" ? 省略 : custom
 */
fun AgentQuestionAnswer.normalized(multiSelect: Boolean): AgentQuestionAnswer {
    val trimmed = custom.trim()
    return if (trimmed.isEmpty()) {
        copy(custom = "")
    } else if (multiSelect) {
        copy(custom = trimmed)
    } else {
        copy(selected = emptyList(), custom = trimmed)
    }
}
