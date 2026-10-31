package top.tianyan.app.harness.diagnostics

import kotlinx.serialization.Serializable
import top.tianyan.app.harness.*
import top.tianyan.app.harness.session.SessionContextProjection

@Serializable
enum class ContextStage { HISTORY, COMPACTION, RETAINED_HISTORY, USER_LIMIT, IMAGE_DOWNSCALE, BYTE_LIMIT, API_BYTE_LIMIT }

@Serializable
enum class ContextAction { TOOL_RESULT_SHORTENED, TOOL_ARGUMENTS_SHORTENED, HISTORY_FOLDED, USER_TEXT_SHORTENED,
    MEDIA_CHANGED, MESSAGE_ADDED, MESSAGE_REMOVED, PROJECTION_CHANGED }

@Serializable
data class ContextSource(val messageId: String?, val entryId: String?, val sequence: Long?, val origin: String?)

@Serializable
data class ContextDecision(val stage: ContextStage, val action: ContextAction, val source: ContextSource?,
    val beforeTextTokens: Int, val afterTextTokens: Int)

@Serializable
data class ContextStageUsage(val stage: ContextStage, val beforeMessages: Int, val afterMessages: Int,
    val beforeTextTokens: Int, val afterTextTokens: Int, val changedMessages: Int)

@Serializable
data class ContextContribution(val kind: String, val count: Int, val estimatedTextTokens: Int)

@Serializable
data class ContextProtection(val source: ContextSource?, val reason: String)

/** Metadata only: never stores message text, parameters, paths, tool outputs or content fingerprints. */
@Serializable
data class ContextGovernanceReport(
    val budgetTokens: Int,
    val compactionEnabled: Boolean,
    val contributions: List<ContextContribution>,
    val stages: List<ContextStageUsage>,
    val decisions: List<ContextDecision>,
    val omittedDecisionCount: Int,
    val estimatedFinalMessageTokens: Int,
    val estimatedFinalMessageBytes: Int,
    val allocations: List<ContextAllocation> = emptyList(),
    val protections: List<ContextProtection> = emptyList(),
    val omittedProtectionCount: Int = 0,
    val serializedBodyBytes: Long? = null,
    val bodyEstimateDifference: Long? = null,
)

/** One assembly owns one trace. Reset on reassembly; retries reuse an immutable report for that view. */
class ContextGovernanceTrace {
    private var budget = 0
    private var enabled = false
    private var sources = emptyMap<String, ContextSource>()
    private val stages = mutableListOf<ContextStageUsage>()
    private val decisions = mutableListOf<ContextDecision>()
    private val contributions = mutableListOf<ContextContribution>()
    private var omitted = 0
    private var finalTokens = 0
    private var finalBytes = 0
    private var allocations = emptyList<ContextAllocation>()
    private val protections = mutableListOf<ContextProtection>()
    private var omittedProtections = 0

    fun begin(projection: SessionContextProjection, budgetTokens: Int, compactionEnabled: Boolean) {
        budget = budgetTokens
        enabled = compactionEnabled
        sources = projection.projection.messages.associate { entry -> entry.message.id to ContextSource(
            safeId(entry.message.id), safeId(entry.sourceEntryId), entry.sourceSequence, entry.origin.name) }
        stages.clear(); decisions.clear(); contributions.clear()
        omitted = 0; finalTokens = 0; finalBytes = 0
        allocations = emptyList(); protections.clear(); omittedProtections = 0
    }

    fun allocations(values: List<ContextAllocation>) {
        allocations = values.groupBy { it.kind }.map { (kind, group) -> ContextAllocation(kind,
            group.sumOf { it.beforeTokens }, group.sumOf { it.allocatedTokens },
            if (group.any { it.reason == "BACKGROUND_BUDGET" }) "BACKGROUND_BUDGET" else group.first().reason) }
    }

    fun contribution(kind: String, texts: Collection<String>) {
        // Call sites use fixed source categories; user/plugin labels never become diagnostic metadata.
        require(kind in setOf("system", "summary", "recall", "failure_memory", "plugin_context"))
        contributions.removeAll { it.kind == kind }
        contributions += ContextContribution(kind, texts.count { it.isNotBlank() }, texts.sumOf(ContextWindowPolicy::estimateTokens))
    }

    fun messages(stage: ContextStage, before: List<HarnessMessage>, after: List<HarnessMessage>) {
        val remaining = after.associateBy { it.id }
        var changed = 0
        before.forEach { original ->
            val projected = remaining[original.id]
            if (original == projected || (stage == ContextStage.COMPACTION && projected != null)) return@forEach
            changed++
            val action = when {
                projected == null -> if (stage == ContextStage.COMPACTION) ContextAction.HISTORY_FOLDED else ContextAction.MESSAGE_REMOVED
                original is ToolCall && projected is ToolCall && original.args != projected.args -> ContextAction.TOOL_ARGUMENTS_SHORTENED
                original is ToolResult && projected is ToolResult && original.output != projected.output -> ContextAction.TOOL_RESULT_SHORTENED
                original is UserMessage && projected is UserMessage && original.imageUrls != projected.imageUrls -> ContextAction.MEDIA_CHANGED
                original is UserMessage && projected is UserMessage && original.text != projected.text -> ContextAction.USER_TEXT_SHORTENED
                else -> ContextAction.PROJECTION_CHANGED
            }
            decision(ContextDecision(stage, action, sources[original.id] ?: ContextSource(safeId(original.id), null, null, null),
                textTokens(original), projected?.let(::textTokens) ?: 0))
        }
        val previousIds = before.mapTo(hashSetOf()) { it.id }
        after.filter { it.id !in previousIds }.forEach {
            changed++
            decision(ContextDecision(stage, ContextAction.MESSAGE_ADDED, ContextSource(safeId(it.id), null, null, null), 0, textTokens(it)))
        }
        stages += ContextStageUsage(stage, before.size, after.size, before.sumOf(::textTokens), after.sumOf(::textTokens), changed)
        if (stage == ContextStage.HISTORY) {
            val reasons = HistoryProtectionPolicy.reasons(before)
            before.filter { it == remaining[it.id] }.forEach { message ->
                reasons[message.id]?.let { reason ->
                    if (protections.size < MAX_DECISIONS) protections += ContextProtection(sources[message.id], reason.name)
                    else omittedProtections++
                }
            }
        }
    }

    fun api(before: List<ApiMessage>, after: List<ApiMessage>) {
        val changed = before.indices.count { before[it] != after.getOrNull(it) }
        stages += ContextStageUsage(ContextStage.API_BYTE_LIMIT, before.size, after.size,
            before.sumOf(::apiTextTokens), after.sumOf(::apiTextTokens), changed)
        if (changed > 0) decision(ContextDecision(ContextStage.API_BYTE_LIMIT, ContextAction.PROJECTION_CHANGED,
            null, before.sumOf(::apiTextTokens), after.sumOf(::apiTextTokens)))
    }

    fun finish(messages: List<ApiMessage>) {
        finalTokens = ContextWindowPolicy.estimateApiMessages(messages)
        finalBytes = ContextWindowPolicy.estimateApiPayloadBytes(messages)
    }

    fun report(bodyBytes: Long? = null) = ContextGovernanceReport(budget, enabled, contributions.toList(), stages.toList(),
        decisions.toList(), omitted, finalTokens, finalBytes, allocations.toList(), protections.toList(), omittedProtections,
        bodyBytes, bodyBytes?.minus(finalBytes.toLong()))

    private fun decision(value: ContextDecision) {
        if (decisions.size < MAX_DECISIONS) decisions += value else omitted++
    }

    private fun textTokens(message: HarnessMessage): Int = when (message) {
        is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
        is AssistantText -> ContextWindowPolicy.estimateTokens(message.text) + ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString()) + ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
        else -> 0
    }

    private fun apiTextTokens(message: ApiMessage) = ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
        ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) + message.tool_calls.orEmpty().sumOf {
            ContextWindowPolicy.estimateTokens(it.function.name) + ContextWindowPolicy.estimateTokens(it.function.arguments) }

    private fun safeId(value: String) = value.takeIf { it.length in 1..64 && it.all { ch ->
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' } }

    companion object { const val MAX_DECISIONS = 32 }
}
