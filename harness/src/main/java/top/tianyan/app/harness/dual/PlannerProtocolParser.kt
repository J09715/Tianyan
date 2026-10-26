package top.tianyan.app.harness.dual

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型无关的 Planner 决策协议解析器（Model-Agnostic Protocol Parser）。
 *
 * 核心目标：
 * 天衍支持任何 LLM（OpenAI, Anthropic Claude, Google Gemini, DeepSeek, 阿里通义千问, 智谱 GLM, 本地 Ollama 等）。
 * 本解析器专为多模型输出设计，具备高度容错：
 * 1. 优先提取 Markdown 代码块 ```json ... ```；
 * 2. 兜底提取前后带有杂质文本的裸 JSON 对象 `{ ... }`；
 * 3. 对小参数量或未严格遵循 JSON 的自然语言回复，支持中英文完成信号智能识别；
 * 4. 支持多步骤 DAG 依赖拆解（INIT_PLAN / REPLAN / EXECUTE_STEP）；
 * 5. 彻底解耦，不依赖特定厂商私有结构。
 */
object PlannerProtocolParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 决策协议的已知顶层字段；裸 JSON 兜底提取需命中其一才被接受为决策对象。 */
    private val KNOWN_DECISION_KEYS = setOf("thought", "action", "plan", "steps", "step", "finalReport")

    fun parse(text: String, currentSteps: List<PlanStep>): PlannerDecision {
        val jsonPattern = Regex("""```(?:json)?\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(text)
        val rawJson = match?.groupValues?.get(1) ?: text.substringAfter("{", "").let {
            if (it.isNotBlank()) "{" + it.substringBeforeLast("}") + "}" else ""
        }

        val parsed = runCatching {
            json.parseToJsonElement(rawJson).jsonObject
        }.getOrNull()?.let { obj ->
            // 代码块中的 JSON 可信；裸 JSON 兜底提取（首个 { 到末个 }）可能把散文中的杂散
            // 花括号误当决策对象，这里要求至少含一个决策协议已知字段才接受，否则走自然语言启发式。
            if (match != null || obj.keys.any { it in KNOWN_DECISION_KEYS }) obj else null
        }

        if (parsed == null) {
            val lower = text.lowercase()
            // 英文完成信号用词边界精确匹配，避免 "uncompleted"/"unfinished" 误命中 "completed"/"finished" 触发 FINISH
            val isCompleted = listOf("已完成", "全部完成", "实现完毕", "任务完成").any { it in text } ||
                listOf("completed", "all done", "finished", "all tasks are completed")
                    .any { Regex("(^|[^a-z])${Regex.escape(it)}([^a-z]|$)").containsMatchIn(lower) }
            return if (isCompleted) {
                PlannerDecision.Finish(finalReport = text, completedSteps = currentSteps)
            } else {
                // 兜底 id 避开既有步骤 id：撞 id 时协调器会按旧状态保留而静默丢弃新指令
                val step = PlanStep(
                    id = nextFallbackStepId(currentSteps),
                    title = "执行下一步",
                    instruction = text.take(500),
                )
                PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
            }
        }

        val thought = parsed["thought"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val action = parsed["action"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: run {
            if (parsed["plan"] != null || parsed["steps"] != null) "INIT_PLAN" else "EXECUTE_STEP"
        }

        return when (action) {
            "FINISH" -> {
                val report = parsed["finalReport"]?.jsonPrimitive?.contentOrNull ?: text
                PlannerDecision.Finish(finalReport = report, completedSteps = currentSteps)
            }
            "REPLAN" -> {
                val reason = if (thought.isNotBlank()) thought else "规划方案微调"
                val planList = parsePlanArray(parsed) ?: currentSteps
                PlannerDecision.Replan(reason = reason, newSteps = planList)
            }
            "INIT_PLAN" -> {
                val planList = parsePlanArray(parsed)
                if (!planList.isNullOrEmpty()) {
                    PlannerDecision.InitializePlan(thought = thought, plan = planList)
                } else {
                    val stepObj = parsed["step"] as? JsonObject
                    if (stepObj != null) {
                        val step = parseStepObject(stepObj, defaultId = "step_1", defaultInstruction = thought)
                        PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
                    } else {
                        PlannerDecision.InitializePlan(thought = thought, plan = currentSteps)
                    }
                }
            }
            else -> {
                val planList = parsePlanArray(parsed)
                if (!planList.isNullOrEmpty() && currentSteps.isEmpty()) {
                    PlannerDecision.InitializePlan(thought = thought, plan = planList)
                } else {
                    val stepObj = parsed["step"] as? JsonObject
                    // 兜底 id 避开既有步骤 id：撞 id 时协调器会按旧状态保留而静默丢弃新指令
                    val fallbackId = nextFallbackStepId(currentSteps)
                    val step = if (stepObj != null) {
                        parseStepObject(stepObj, defaultId = fallbackId, defaultInstruction = thought.ifBlank { text })
                    } else {
                        PlanStep(
                            id = fallbackId,
                            title = "工序 $fallbackId",
                            instruction = thought.ifBlank { text },
                        )
                    }
                    PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
                }
            }
        }
    }

    /** 生成不与既有步骤冲突的兜底步骤 id（step_N 递增直到不撞车）。 */
    private fun nextFallbackStepId(currentSteps: List<PlanStep>): String {
        val existing = currentSteps.mapTo(hashSetOf()) { it.id }
        var n = currentSteps.size + 1
        while ("step_$n" in existing) n++
        return "step_$n"
    }

    private fun parsePlanArray(parsed: JsonObject): List<PlanStep>? {
        val array = (parsed["plan"] as? JsonArray) ?: (parsed["steps"] as? JsonArray) ?: return null
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            parseStepObject(obj, defaultId = "step_${index + 1}", defaultInstruction = "")
        }
    }

    private fun parseStepObject(stepObj: JsonObject, defaultId: String, defaultInstruction: String): PlanStep {
        val stepId = stepObj["id"]?.jsonPrimitive?.contentOrNull ?: defaultId
        val title = stepObj["title"]?.jsonPrimitive?.contentOrNull ?: "工序 $stepId"
        val instruction = stepObj["instruction"]?.jsonPrimitive?.contentOrNull ?: defaultInstruction
        val expected = stepObj["expectedOutcome"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dependencies = runCatching {
            stepObj["dependencies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull().orEmpty()

        return PlanStep(
            id = stepId,
            title = title,
            instruction = instruction,
            expectedOutcome = expected,
            dependencies = dependencies,
        )
    }
}
