package top.tianyan.app.harness.prompt

import kotlin.math.ln
import top.tianyan.app.core.database.AgentContextRepository
import top.tianyan.app.core.database.AgentMemoryEntity

/**
 * 逐轮记忆召回器（低权威背景资料层，对齐 Reasonix Context Engine v2 的 recall 语义）。
 *
 * 检索：BM25 + **CJK 字符 bigram**（要求真实词重叠，而非散落常用字误命中；拉丁词按整词小写）。
 * project 事实小幅加权；stale 按 updatedAt 年龄降权（降权不删除）；泛化轮次（"继续""ok"）
 * 不触发召回。命中按预算截断（≤[MAX_RECALL_FACTS] 条，块总量 ≤[MAX_RECALL_BLOCK_CHARS] 字符）。
 * 与旧 LIKE 方案的行为差异：不再"无命中就注入最近记忆"——不相关记忆是噪声与 token 浪费，
 * 必须常驻的指令应 pinned（pinned 走 system prompt 稳定前缀）。
 *
 * 关键契约：块内容（记忆召回 + 计划看板）**每用户轮只计算一次并持久化**
 * （recall_context entry），此后该轮的 provider 投影永远携带同一段前缀字节。记忆库后续变化
 * （模型写记忆、用户改 pinned）与计划后续推进都不影响历史轮的已持久化字节——这是 prefix
 * cache 稳定性的前提，也是它与旧「system prompt 尾部注入」方案的本质区别：system prompt
 * 任何逐轮变化都会击穿其后全部对话的缓存，而追加在 user 轮上的前缀只影响该轮之后的新增内容
 * （本来就未缓存）。
 */
class MemoryRecallSelector(
    private val agentContextDao: AgentContextRepository,
) {
    /**
     * 计算某条用户消息的召回后缀块；无命中返回空串。结果一旦持久化便不再重算，
     * 因此这里的检索口径（查询词、新鲜度、pinned 排除）只在该轮首次组装时生效。
     */
    suspend fun recallBlock(
        projectOwnerId: String,
        sessionId: String,
        userMessage: String,
    ): String {
        val now = System.currentTimeMillis()
        // pinned 走 system prompt 稳定前缀（最高权威），不再重复进召回；
        // expiresAt 硬过期的条目完全排除。
        val pinnedIds = runCatching { agentContextDao.getPinnedMemories(projectOwnerId, sessionId) }
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { it.id }
        val candidates = runCatching {
            agentContextDao.getMemoriesForContext(
                projectOwnerId = projectOwnerId,
                sessionId = sessionId,
                limit = MAX_RECALL_CANDIDATES,
            )
        }
            .getOrDefault(emptyList())
            .filter { entity ->
                val expires = entity.expiresAt
                entity.id !in pinnedIds && (expires == null || expires > now)
            }
        if (candidates.isEmpty()) return ""
        val selected = selectRecall(userMessage, candidates, now)
        return renderBlock(selected)
    }

    /**
     * 用户轮前缀块：记忆召回 + 任务计划看板，合并为**同一段持久化字节**。
     *
     * 合成一个块而非两条 entry，是因为调用方（[ApiContextAssembler]）的幂等门控是按
     * 「该用户轮是否已有前缀块」判定的——分两条会各自独立判定，反而更容易漂移。
     */
    suspend fun turnPrefixBlock(
        projectOwnerId: String,
        sessionId: String,
        userMessage: String,
    ): String {
        val memory = recallBlock(projectOwnerId, sessionId, userMessage)
        val plan = planBlock(sessionId)
        return listOf(memory, plan).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * 任务计划看板块（低权威背景资料层）。
     *
     * 计划随 plan 工具的每一步推进而变；放在 system prompt 里意味着「每推进一个步骤，
     * 整段 system 前缀失效」。作为用户轮前缀块的一部分，它只在**该用户轮首次组装时**
     * 取一次快照并冻结：轮内的步骤推进仍通过 plan 工具的返回文本体现，不再改写已发出的字节。
     * 压缩后计划的可见性由「最新用户轮的后缀落在保留窗口内」保证。
     */
    suspend fun planBlock(sessionId: String): String {
        if (sessionId.isBlank()) return ""
        val plan = runCatching { agentContextDao.getActivePlan(sessionId) }.getOrNull() ?: return ""
        if (plan.status != "active") return ""
        val goal = plan.goal.trim().take(MAX_PLAN_GOAL_CHARS)
        val steps = plan.stepsJson.trim().take(MAX_PLAN_STEPS_CHARS)
        return buildString {
            appendLine("<task_plan>")
            appendLine("以下为本会话当前的活跃任务计划与进度（背景资料，供接续执行；实际进度以 plan 工具返回为准）：")
            appendLine("目标：$goal")
            appendLine("步骤与状态：$steps")
            append("</task_plan>")
        }
    }

    companion object {
        private const val MAX_RECALL_CANDIDATES = 64
        /** 注入条数预算（对齐 Reasonix 的 ≤4 条口径，略放宽为 5）。 */
        internal const val MAX_RECALL_FACTS = 5
        internal const val MAX_PROMPT_MEMORY_KEY_CHARS = 128
        internal const val MAX_PROMPT_MEMORY_VALUE_CHARS = 512

        /** 单个召回后缀块的字符预算（含声明行）；持久化字节随轮累积，必须有界。 */
        internal const val MAX_RECALL_BLOCK_CHARS = 4_000

        /** 计划看板的字符预算：stepsJson 是模型自产 JSON，必须有界以免撑大每轮请求。 */
        internal const val MAX_PLAN_GOAL_CHARS = 200
        internal const val MAX_PLAN_STEPS_CHARS = 1_200

        /**
         * 渲染为 user 轮后缀：XML 包裹 + 低权威声明，格式稳定（进入持久化字节，不可随意改版）。
         * 总量受 [MAX_RECALL_BLOCK_CHARS] 约束；超预算按序丢弃靠后条目。
         */
        internal fun renderBlock(memories: List<AgentMemoryEntity>): String {
            if (memories.isEmpty()) return ""
            val body = StringBuilder()
            var used = 0
            for (entity in memories) {
                val line = "- [${entity.scope}/${entity.kind}] ${entity.key.take(MAX_PROMPT_MEMORY_KEY_CHARS)}: " +
                    entity.value.take(MAX_PROMPT_MEMORY_VALUE_CHARS)
                if (used > 0 && used + line.length + 1 > MAX_RECALL_BLOCK_CHARS) break
                body.appendLine(line)
                used += line.length + 1
            }
            if (body.isBlank()) return ""
            return buildString {
                appendLine("<recalled_memory>")
                appendLine(
                    "以下为按本条消息检索到的长期记忆（低权威背景资料）：可能过期或不完整，仅在相关时参考；" +
                        "与当前请求或系统规则冲突时，以当前请求与系统规则为准。",
                )
                append(body)
                append("</recalled_memory>")
            }
        }

        /** BM25 参数（标准取值）。 */
        private const val K1 = 1.2
        private const val B = 0.75

        /** 泛化短语：短消息整体命中即不触发召回（"继续帮我改代码"不受影响）。 */
        private val GENERIC_PHRASES = listOf(
            "继续", "接着来", "然后呢", "好的", "收到", "可以", "开始吧", "行", "嗯", "哦",
            "continue", "go on", "keep going", "ok", "okay", "next", "yes",
        )

        /** 泛化 token：分词后逐个剔除，剩下的信息词才是有效查询。 */
        private val GENERIC_TERMS = setOf(
            "继续", "好的", "收到", "可以", "开始", "然后", "一下", "这个", "那个", "怎么", "什么",
            "帮忙", "请", "我", "你", "的", "了", "吗", "吧", "呢", "看", "讲", "说",
            "continue", "please", "the", "and", "what", "how",
        )

        /**
         * BM25 召回选择（纯函数）：泛化轮次抑制 → 分词 → BM25 打分 →
         * scope 加权（project 1.2 / global 1.0 / session 0.9）× 新鲜度降权 → 预算截断。
         */
        internal fun selectRecall(
            query: String,
            candidates: List<AgentMemoryEntity>,
            now: Long = System.currentTimeMillis(),
        ): List<AgentMemoryEntity> {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty()) return emptyList()
            val shortGeneric = normalized.length <= 4 &&
                GENERIC_PHRASES.any { normalized == it || normalized.contains(it) }
            if (shortGeneric || normalized in GENERIC_PHRASES) return emptyList()

            val queryTerms = tokenize(query).filter { it !in GENERIC_TERMS }
            if (queryTerms.isEmpty()) return emptyList()

            val documents = candidates.map { entity ->
                tokenize(entity.key + "\n" + entity.value)
            }
            if (documents.all { it.isEmpty() }) return emptyList()
            val scores = bm25(queryTerms, documents)

            data class Scored(val entity: AgentMemoryEntity, val score: Double)

            val ranked = candidates.indices.mapNotNull { index ->
                val score = scores[index]
                if (score <= 0.0) return@mapNotNull null
                val entity = candidates[index]
                val scopeBoost = when (entity.scope) {
                    "project" -> 1.2
                    "session" -> 0.9
                    else -> 1.0
                }
                val ageDays = ((now - entity.updatedAt).coerceAtLeast(0)) / 86_400_000.0
                val freshness = when {
                    ageDays < 30 -> 1.0
                    ageDays < 180 -> 0.85
                    else -> 0.7
                }
                Scored(entity, score * scopeBoost * freshness)
            }.sortedByDescending { it.score }

            val selected = ranked.take(MAX_RECALL_FACTS).map { it.entity }
            return selected
        }

        /**
         * 分词：拉丁/数字按整词小写；CJK 连续段切 2-gram（单字段保留单字）。
         * bigram 让"调用链"命中"调用链路分析"这类真实词重叠，同时避免
         * LIKE '%测试%' 被"测试服"这类散字误命中。
         */
        internal fun tokenize(text: String): List<String> {
            val terms = mutableListOf<String>()
            LATIN_WORD.findAll(text).forEach { terms += it.value.lowercase() }
            CJK_SEGMENT.findAll(text).forEach { segment ->
                val value = segment.value
                if (value.length == 1) {
                    terms += value
                } else {
                    for (index in 0 until value.length - 1) {
                        terms += value.substring(index, index + 2)
                    }
                }
            }
            return terms
        }

        private fun bm25(queryTerms: List<String>, documents: List<List<String>>): List<Double> {
            val documentFrequency = HashMap<String, Int>()
            documents.forEach { doc ->
                doc.distinct().forEach { term -> documentFrequency.merge(term, 1, Int::plus) }
            }
            val total = documents.size.coerceAtLeast(1)
            val avgLength = documents.sumOf { it.size }.toDouble().div(total).coerceAtLeast(1.0)
            return documents.map { doc ->
                val termFrequency = HashMap<String, Int>()
                doc.forEach { term -> termFrequency.merge(term, 1, Int::plus) }
                val length = doc.size.coerceAtLeast(1)
                queryTerms.distinct().sumOf { term ->
                    val df = documentFrequency.getOrDefault(term, 0)
                    val frequency = termFrequency.getOrDefault(term, 0).toDouble()
                    if (frequency == 0.0 || df == 0) {
                        0.0
                    } else {
                        val idf = ln(1 + (total - df + 0.5) / (df + 0.5))
                        idf * (frequency * (K1 + 1)) /
                            (frequency + K1 * (1 - B + B * length / avgLength))
                    }
                }
            }
        }

        private val LATIN_WORD = Regex("[A-Za-z0-9_]+")
        private val CJK_SEGMENT = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff]+")
    }
}
