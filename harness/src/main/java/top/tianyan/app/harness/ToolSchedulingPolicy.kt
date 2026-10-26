package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject

/** Only tools without shared-state mutations may overlap within a round. */
internal object ToolSchedulingPolicy {
    private val readOnlyTools = setOf(
        HarnessTool.READ,
        HarnessTool.HISTORY_SEARCH,
        HarnessTool.HISTORY_READ,
        HarnessTool.LOAD_RULE,
    )

    /**
     * 该调用能否进入回合内的并发组。
     *
     * 子智能体此前是无条件屏障，导致主智能体一轮里发出的多个 `invoke_subagent`
     * 只能逐个整批执行（每批最坏 15 分钟）。实际上子智能体自己按 write_paths 协调写租约，
     * 批次之间是否安全只取决于两点：写路径集合是否相交、有没有整工作区独占（`["*"]`）。
     * 后者由 [conflicts] 在组内两两判定，这里只回答"这次调用本身是否具备并行资格"：
     * 解析不出子任务（参数非法）时保守回退为屏障。
     */
    fun isParallelSafe(tool: HarnessTool, args: JsonObject? = null): Boolean = when (tool) {
        in readOnlyTools -> true
        HarnessTool.SUBAGENT -> subagentWritePaths(args) != null
        else -> false
    }

    fun needsMutationLock(tool: HarnessTool): Boolean =
        tool !in readOnlyTools && tool != HarnessTool.SUBAGENT && tool != HarnessTool.BASE

    /**
     * 同一并发组内两个调用是否必须错开。
     *
     * 子智能体会真正落盘写文件，因此与它同组的成员只有两类：写路径不相交的其他子智能体，
     * 以及不落盘的只读工具。判定分三种情况：
     * - 两侧都不是子智能体：组内只剩只读工具，互不冲突；
     * - 一侧是子智能体、另一侧是只读工具：子智能体声明了写路径（或参数解析失败、去向未知）
     *   就必须断组，否则同轮先 `invoke_subagent` 写 `harness/a.kt` 再 `read` 同路径会读到
     *   半成品；空 write_paths 的只读子任务不落盘，仍可与只读工具同组；
     * - 两侧都是子智能体：任一方解析失败即冲突；`["*"]` 这类整工作区租约与任何批次（含
     *   只声明读的批次）都冲突；只声明读的批次（空 write_paths）与任何会落盘的批次都断组——
     *   只读子任务照样会 `read`，与写批次并发同样会读到半成品，口径对齐编排层
     *   `buildWriteCleanWaves`（只读波与任何写入波分离）；其余情况只在双方写路径相交时冲突。
     */
    fun conflicts(
        leftTool: HarnessTool,
        leftArgs: JsonObject?,
        rightTool: HarnessTool,
        rightArgs: JsonObject?,
    ): Boolean {
        val left = writeScope(leftTool, leftArgs)
        val right = writeScope(rightTool, rightArgs)
        if (left == null) return right != null && right.blocksReadOnly()
        if (right == null) return left.blocksReadOnly()
        val leftPaths = left.paths ?: return true
        val rightPaths = right.paths ?: return true
        if (leftPaths.isEmpty() && rightPaths.isEmpty()) return false
        // `["*"]` 是整工作区独占租约：连"只声明读"的批次也不能同组，它可能读到半成品。
        if (claimsWholeWorkspace(leftPaths) || claimsWholeWorkspace(rightPaths)) return true
        // 到这里已经排除"双方都只声明读"：一侧空、另一侧要落盘时必须断组。
        // 只读子任务照样会 read，与写批次并发仍可能读到半成品（与编排层只读波/写入波分离同源）。
        if (leftPaths.isEmpty() || rightPaths.isEmpty()) return true
        return leftPaths.any { candidate ->
            rightPaths.any { existing -> writePathsConflict(existing, candidate) }
        }
    }

    /** 整工作区租约（`*` / `.`）的判定与编排层同源，避免两处各写一套口径。 */
    private fun claimsWholeWorkspace(paths: Set<String>): Boolean =
        paths.any(::isWholeWorkspaceWritePath)

    /** 子智能体的写声明；`paths == null` 表示参数解析失败、实际去向未知。 */
    private class WriteScope(val paths: Set<String>?) {
        /**
         * 只读工具在它旁边并发是否可能读到半成品。
         *
         * 解析失败时按"会写"处理：宁可让只读工具等一组，也不要赌没解析出来的参数不落盘。
         */
        fun blocksReadOnly(): Boolean = paths == null || paths.isNotEmpty()
    }

    /** 非子智能体返回 null：并行组里的其余成员只可能是只读工具。 */
    private fun writeScope(tool: HarnessTool, args: JsonObject?): WriteScope? =
        if (tool == HarnessTool.SUBAGENT) WriteScope(subagentWritePaths(args)) else null

    /**
     * 一次 `invoke_subagent` 调用声明的写路径并集。
     *
     * 复用 [SubagentArgsParser] 与 [normalizeWritePath]、[writePathsConflict]，
     * 保证回合级判定与编排层 `buildWriteCleanWaves` 的口径完全一致——两处各写一套规则
     * 迟早会漂移成"调度认为安全、租约认为冲突"的静默踩踏。解析不出任何任务时返回 null。
     */
    internal fun subagentWritePaths(args: JsonObject?): Set<String>? {
        val parsed = args ?: return null
        val tasks = SubagentArgsParser.parse(parsed)
        if (tasks.isEmpty()) return null
        return tasks.flatMap { it.writePaths }.mapTo(linkedSetOf()) { normalizeWritePath(it) }
    }

    // Memory/plan/scratchpad actions can mutate state; MCP and unknown capabilities are exclusive.
    // Base remains a round barrier, but a long shell command must not hold the workspace lock
    // across sessions. Shell effects have no structured path lease; concurrent commands may
    // touch the same files. Runtime admission separately serializes known memory-heavy commands.
    // Subagents hold no parent workspace lock; they are admitted to a concurrency group only when
    // their declared write paths are pairwise disjoint (see [conflicts]).
}
