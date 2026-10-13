package top.tianyan.app.harness.evidence

/**
 * 交付闸门：把「改动 + 验证」的事实折算成一个可执行的颜色结论。
 *
 * 关键设计（硬教训，不要改）：
 * - YELLOW 是「可交付」的。把「没验证」做成硬阻塞，模型会学会编造验证，
 *   那比不验证更糟。闸门要的是诚实，不是成功。
 * - 只有晚于最后一次改动的验证才算数。改动之前的绿是「陈旧的绿」，
 *   它证明的是旧代码，拿来背书新代码就是造假。
 */
object DeliveryGate {

    enum class GateColor { GREEN, YELLOW, RED }

    data class GateVerdict(
        val color: GateColor,
        val canDeliver: Boolean,
        val reason: String,
        val nextAction: String? = null,
        /** no_changes | verified | unverified | owned_failure | blocked */
        val attribution: String,
    )

    fun evaluate(state: EvidenceState): GateVerdict {
        // 规则 1：没有改动就无从验证，直接放行，避免纯问答/纯报告被闸门干扰。
        if (state.filesModified.isEmpty()) {
            return GateVerdict(
                color = GateColor.GREEN,
                canDeliver = true,
                reason = "没有文件改动，无需验证。",
                nextAction = null,
                attribution = "no_changes",
            )
        }

        // 只有晚于最后一次改动的验证才算「新鲜」。
        //
        // 为什么必须卡时间戳：改动**之前**跑出来的绿，证明的是旧代码。
        // 拿它给新改动背书，就是「改完代码没重跑的全绿」——那是最典型的假绿。
        val fresh = state.verifications.filter { it.at >= state.lastMutationAt }
        // fresh 的顺序即 verifications 的顺序（filter 保序），takeLast 取最近一次。
        val newestFresh = fresh.lastOrNull()

        return when {
            // 规则 2：最新一条新鲜验证是失败 → 阻塞，且必须点名失败的命令。
            // 这是「自己的失败要认领」：改完代码跑挂了还说完成，是最贵的一种谎。
            newestFresh != null && !newestFresh.passed -> GateVerdict(
                color = GateColor.RED,
                canDeliver = false,
                reason = "最新改动后的验证失败：${newestFresh.command}。改动未通过验证，不能声称完成。",
                nextAction = "先修复失败的验证，再声称完成。",
                attribution = "owned_failure",
            )

            // 规则 3：最新一条新鲜验证通过 → 放行。
            newestFresh != null && newestFresh.passed -> GateVerdict(
                color = GateColor.GREEN,
                canDeliver = true,
                reason = "最后一次改动后已有通过的验证：${newestFresh.command}。",
                nextAction = null,
                attribution = "verified",
            )

            // 规则 4：改了文件但最后一次改动之后没有任何通过记录 → 黄灯，可交付但不许吹。
            // 注意：即便存在改动之前通过的验证也走这条——那是陈旧的绿，不作数。
            else -> GateVerdict(
                color = GateColor.YELLOW,
                canDeliver = true,
                reason = "本回合有文件改动，但最后一次改动之后没有任何验证记录（改动之前的验证结果已失效）。" +
                    "这些改动处于未验证状态，不得声称已验证或测试通过。",
                nextAction = "运行相关验证（测试/构建/类型检查）后再声称完成。",
                attribution = "unverified",
            )
        }
    }
}
