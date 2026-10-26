package top.tianyan.app.harness

/**
 * 工具轮次预算：每段 [roundsPerSegment] 轮，段与段之间最多自动续跑 [maxContinuations] 次。
 *
 * 分段的意义不是"把上限调大"，而是把上限从硬失败降级成软检查点：每段用尽时上层会先让模型
 * 收束并落盘进度，再续一段新预算继续跑。无人值守场景因此不必靠用户点一下才能往下走，
 * 同时总预算仍然有界，跑飞仍由循环检测与连续失败熔断兜底。
 */
internal class RoundBudget(roundsPerSegment: Int, maxContinuations: Int) {
    val roundsPerSegment: Int = roundsPerSegment.coerceAtLeast(1)
    val maxContinuations: Int = maxContinuations.coerceAtLeast(0)

    /** 当前段内已完成的轮数。 */
    var roundInSegment: Int = 0
        private set

    /** 已经用掉的续跑次数。 */
    var continuations: Int = 0
        private set

    val exhausted: Boolean get() = roundInSegment >= roundsPerSegment
    val remainingInSegment: Int get() = roundsPerSegment - roundInSegment

    /** 跨段累计轮数，用于任务进度展示，避免续跑后进度回退。 */
    val totalRounds: Int get() = continuations * roundsPerSegment + roundInSegment

    /** 全部段加起来的总轮数上限。 */
    val totalBudget: Int get() = roundsPerSegment * (maxContinuations + 1)

    fun advance() {
        roundInSegment++
    }

    fun canContinue(): Boolean = continuations < maxContinuations

    /** 开启下一段预算，返回这是第几次续跑；调用前须先确认 [canContinue]。 */
    fun beginNextSegment(): Int {
        check(canContinue()) { "续跑次数已用尽" }
        roundInSegment = 0
        return ++continuations
    }
}
