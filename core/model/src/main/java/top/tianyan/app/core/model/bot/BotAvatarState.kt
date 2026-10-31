package top.tianyan.app.core.model.bot

/**
 * 智能体运行态视觉表现状态。
 * 用于驱动像素头像动态表情、帧动画与微动效。
 */
enum class BotAvatarState {
    /** 正常闲置/就绪。 */
    IDLE,

    /** 活跃输出中（通用说话/流式吐字）。 */
    ACTIVE,

    /** 思考/推理中（正在进行 Chain-of-Thought 推理）。 */
    THINKING,

    /** 执行工具中（跑 Shell、执行 PRoot、调用 MCP 工具等）。 */
    EXECUTING,

    /** 任务受阻/需人工介入（对应 report-assignment blocked 或提问等待）。 */
    BLOCKED,

    /** 任务圆满完成/成功。 */
    SUCCESS,

    /** 任务执行失败/出错。 */
    FAILED,
}
