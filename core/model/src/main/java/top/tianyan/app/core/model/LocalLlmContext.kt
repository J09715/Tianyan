package top.tianyan.app.core.model

/**
 * 本地推理（llama.cpp）的上下文窗口决策。
 *
 * 为什么单独抽成纯逻辑：这个值必须与「模型档案里配置的上下文上限」保持一致，
 * 而它此前只按设备内存硬编码，导致用户在模型配置里改了上下文却不生效——
 * 服务端仍以旧值启动，稍长的对话直接 400
 * （request exceeds the available context size）。
 * 抽出来才能对「配置优先 / 边界收敛」这两条规则做单元测试。
 */
object LocalLlmContext {

    /** 低内存设备（< 6 GiB）的兜底上下文。 */
    const val LOW_MEMORY_CONTEXT = 2048

    /** 常规设备的兜底上下文。 */
    const val DEFAULT_CONTEXT = 4096

    /**
     * 上下文下限：低于此值连系统提示词加一轮工具输出都放不下，
     * 启动后必然立刻 400，不如在启动时就收敛掉。
     */
    const val MIN_CONTEXT = 2048

    /**
     * 上下文上限：llama.cpp 按 ctx 预分配 KV cache，
     * 手机上给太大（如 128k）会在启动阶段就被 OOM killer 干掉。
     */
    const val MAX_CONTEXT = 131_072

    private const val SIX_GIB = 6L * 1024L * 1024L * 1024L

    /**
     * 计算实际传给 llama-server 的 `--ctx-size`。
     *
     * 规则：**用户配置优先**，未配置才按设备内存推断；两者都收敛到 [MIN_CONTEXT, MAX_CONTEXT]。
     * `configured` 传 0 或负数视为「未配置」（与「填了 0」这种无效输入同义），
     * 而不是收敛成 MIN——把无效输入当成一个具体的上限比回退到默认更危险。
     */
    fun resolve(configured: Int?, deviceRamBytes: Long): Int {
        val fallback = if (deviceRamBytes < SIX_GIB) LOW_MEMORY_CONTEXT else DEFAULT_CONTEXT
        val requested = configured?.takeIf { it > 0 } ?: fallback
        return requested.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
    }
}
