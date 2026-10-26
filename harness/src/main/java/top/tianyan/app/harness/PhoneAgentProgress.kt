package top.tianyan.app.harness

/** 明确的失败预算，不把连续无效操作留给提示词自行约束。 */
internal class PhoneAgentProgress {
    private var previousAction: PhoneAgentAction? = null
    private var previousFrame: String? = null
    private var repeated = 0
    private var waits = 0
    private var failures = 0

    fun beforeAction(action: PhoneAgentAction, frame: String): String? {
        if (action is PhoneAgentAction.TakeOver) return null
        if (action is PhoneAgentAction.Finish && failures > 0) return "上一步执行失败，不能将任务报告为完成。"
        waits = if (action is PhoneAgentAction.Wait) waits + 1 else 0
        if (waits > 3) return "连续等待三次仍未恢复，请人工检查页面或网络。"
        repeated = if (action == previousAction && frame == previousFrame) repeated + 1 else 0
        previousAction = action
        previousFrame = frame
        if (action !is PhoneAgentAction.Wait && repeated >= 2) return "连续重复相同操作且画面没有变化，已停止以避免误操作。"
        return null
    }

    fun afterAction(success: Boolean): String? {
        failures = if (success) 0 else failures + 1
        return if (failures >= 2) "连续两次输入或启动失败，已停止，请检查虚拟屏服务。" else null
    }
}
