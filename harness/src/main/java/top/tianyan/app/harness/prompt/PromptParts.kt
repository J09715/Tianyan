package top.tianyan.app.harness.prompt

/**
 * 系统提示词的分节结果，按「可否缓存」拆成两组。
 *
 * ## 为什么要按这个维度拆
 *
 * system prompt 位于请求位置 0，而 provider 的前缀缓存是**精确前缀匹配**：
 * 位置 0 之后任一字节变化，都会让其后**整段对话历史**重新 prefill。
 * 代价随会话长度线性增长，不是一次性的静态前缀开销。
 *
 * 因此只有「会话内常量」才允许进 [frozen]；任何依赖当轮用户消息、
 * 当轮 @ 提及、或计划推进状态的内容都必须进 [dynamic]，
 * 由调用方以「追加到最后一条 user 消息之后」的方式注入 —— 尾部追加
 * 不破坏已缓存前缀。
 *
 * ## 维护须知
 *
 * 往 [frozen] 里加块前先问：它在同一会话内会不会变？
 * 只要答案是「会」，就必须放 [dynamic]。把逐轮变化的块放进 frozen
 * 会让模型看到过期内容（比缓存失效更糟，因为它是静默的）。
 * PromptStabilityTest 会守住这条界线。
 */
data class PromptParts(
    val frozen: List<String>,
    val dynamic: List<String>,
) {
    /** 会话常量前缀；同一会话内应逐字节稳定。 */
    fun frozenText(): String = join(frozen)

    /** 每轮/每用户边界可变部分；追加到 user 消息尾部。 */
    fun dynamicText(): String = join(dynamic)

    /** 兼容旧行为：整体拼接（frozen 在前，dynamic 在后）。 */
    fun allText(): String = join(frozen + dynamic)

    private fun join(blocks: List<String>): String =
        blocks.filter { it.isNotBlank() }.joinToString("\n\n") { it.trim() }
}
