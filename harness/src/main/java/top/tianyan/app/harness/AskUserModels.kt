package top.tianyan.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ask_user 工具的参数模型（对齐 opencode question 工具）：
 * 模型一次可提 1~4 个问题，每题可带候选项（label + 说明）并允许自定义输入。
 * ToolExecutor 校验入参与 UI 渲染问题卡共用同一解析，避免两处口径漂移。
 */
@Serializable
data class AskUserQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskUserOption> = emptyList(),
    /** 有候选项时默认允许自定义输入；纯自由文本问题恒为 true。 */
    val allowCustom: Boolean = true,
    val defaultAnswer: String? = null,
)

@Serializable
data class AskUserOption(
    val label: String,
    val description: String? = null,
    val recommended: Boolean = false,
)

object AskUserQuestions {
    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 6
    const val TOOL_NAME = "ask_user"

    fun parseJson(arguments: String): List<AskUserQuestion> = parse(Json.parseToJsonElement(arguments).jsonObject)

    fun answersJson(answers: List<String>): String = buildJsonArray {
        answers.forEachIndexed { index, answer -> add(buildJsonObject {
            put("index", index)
            put("answer", answer)
        }) }
    }.toString()

    /** 解析并校验 ask_user 入参；不合法时抛 [IllegalArgumentException]（文案可直接回写模型）。 */
    fun parse(args: JsonObject): List<AskUserQuestion> {
        val rawQuestions = args["questions"] as? JsonArray
            ?: throw IllegalArgumentException("questions 必须是问题对象数组")
        if (rawQuestions.isEmpty() || rawQuestions.size > MAX_QUESTIONS) {
            throw IllegalArgumentException("questions 数组长度必须在 1~$MAX_QUESTIONS 之间")
        }
        return rawQuestions.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("questions[$index] 必须是 JSON 对象")
            val question = item["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (question.isEmpty()) {
                throw IllegalArgumentException("questions[$index].question 不能为空")
            }
            val options = (item["options"] as? JsonArray)?.mapIndexed { optionIndex, optionElement ->
                val option = optionElement as? JsonObject
                    ?: throw IllegalArgumentException("questions[$index].options[$optionIndex] 必须是 JSON 对象")
                val label = option["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (label.isEmpty()) {
                    throw IllegalArgumentException("questions[$index].options[$optionIndex].label 不能为空")
                }
                AskUserOption(
                    label = label,
                    description = option["description"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                    recommended = option["recommended"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.orEmpty()
            if (options.size > MAX_OPTIONS) {
                throw IllegalArgumentException("questions[$index].options 最多 $MAX_OPTIONS 项")
            }
            val duplicate = options.groupBy { it.label }.filterValues { it.size > 1 }.keys.firstOrNull()
            if (duplicate != null) {
                throw IllegalArgumentException("questions[$index] 存在重复选项「$duplicate」，请使用互斥的候选项")
            }
            val allowCustom = item["allow_custom"]?.jsonPrimitive?.booleanOrNull ?: options.isNotEmpty()
            AskUserQuestion(
                question = question,
                header = item["header"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                options = options,
                allowCustom = allowCustom || options.isEmpty(),
                defaultAnswer = item["default_answer"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
            )
        }
    }

    /** An automatic assumption is explicitly distinct from a Human answer. */
    fun timeoutOutput(argumentsJson: String, waited: Boolean = true): String {
        val questions = parseJson(argumentsJson)
        val reason = if (waited) "用户在 60 秒内未回复。" else "后台任务不暂停等待用户。"
        return reason + "以下为预设推荐答案（非用户确认），请据此继续完成原任务，不再等待：\n" +
            questions.mapIndexed { index, question ->
                val answer = question.options.firstOrNull { it.recommended }?.label
                    ?: question.options.firstOrNull { it.label.contains("推荐") || it.label.contains("recommended", true) }?.label
                    ?: question.options.firstOrNull()?.label ?: question.defaultAnswer
                    ?: "根据已有上下文自行采用合理默认值继续；不得编造凭据或其他缺失事实。"
                "${index + 1}. ${question.question}\n   → $answer"
            }.joinToString("\n")
    }

    /** 把 UI 提交的答案（`[{"index":0,"answer":"..."}]`）格式化为回写给模型的工具结果。 */
    fun formatAnswers(questionsJson: String, answersJson: String): String {
        val questions = runCatching {
            parse(Json.parseToJsonElement(questionsJson).jsonObject)
        }.getOrNull().orEmpty()
        val answers = runCatching {
            (Json.parseToJsonElement(answersJson) as? JsonArray)
                ?.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val index = item["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                    val answer = item["answer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (answer.isEmpty()) null else index to answer
                }
        }.getOrNull().orEmpty()
        if (questions.isEmpty() || answers.isEmpty()) {
            return "用户已回答 ask_user 提问：$answersJson"
        }
        return buildString {
            append("用户已回答 ask_user 提问：\n")
            answers.forEach { (index, answer) ->
                val question = questions.getOrNull(index)?.question ?: "问题 ${index + 1}"
                append("${index + 1}. $question\n   → $answer\n")
            }
        }.trimEnd()
    }
}
