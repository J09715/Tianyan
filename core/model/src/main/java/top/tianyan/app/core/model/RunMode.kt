package top.tianyan.app.core.model

/**
 * 运行意图模式：与 [ApprovalMode] 正交的第二条轴。
 *
 * [ApprovalMode] 决定「要不要问用户」，本模式决定「这一轮是动手还是只做规划」：
 * - [BUILD]：正常执行，工具按 [ApprovalMode] 走审批；
 * - [PLAN]：只读规划，宿主侧硬拒绝一切会改变状态的工具（不进审批队列）。
 *
 * 二者可叠加：`FULL_ACCESS + PLAN` 的语义是「我全权授权，但这次只让你看」。
 */
enum class RunMode(val id: String) {
    BUILD("build"),
    PLAN("plan");

    companion object {
        /** 未知/空值回落到 BUILD：失败方向朝「不锁死用户」，对齐 [ApprovalMode.fromId]。 */
        fun fromId(id: String?): RunMode = entries.firstOrNull { it.id == id } ?: BUILD
    }
}