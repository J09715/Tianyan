package top.tianyan.app.core.tools

/**
 * 网关服务通知与 App 侧「停止」接收器之间的契约。
 *
 * `tools` 模块不能引用 `app` 模块的 Service/Receiver 类，通知里的动作只能发一条带包名的广播；
 * 接收器（[top.tianyan.app.service.ToolServiceStopReceiver]）注册同一 action。停止语义与工具中心的
 * 停止按钮一致：按当前发行版停服务并关自启动，因此接收器只需 toolId。
 */
object ToolServiceNotificationContract {
    const val ACTION_STOP = "top.tianyan.app.action.STOP_TOOL_SERVICE"
    const val EXTRA_TOOL_ID = "top.tianyan.app.extra.TOOL_SERVICE_TOOL_ID"
}
