package top.tianyan.app.core.tools

/**
 * 网关服务通知的窄接口。
 *
 * 生命周期逻辑（[ToolServiceController]）只依赖这四个动作，Android 通知实现与单测替身都落在接口之后，
 * 控制器不必知道渠道、图标或 PendingIntent 的细节。
 */
interface ServiceNotificationSink {
    /** 网关启动成功后贴一条常驻通知；内容不含 Token 或任何凭据。 */
    fun showService(distroId: String, toolId: String, toolName: String, detail: String)

    fun hideService(distroId: String, toolId: String)

    fun hideAllServices()

    /** 已贴出但尚未撤销的通知，用于跨进程重启后的对账。 */
    fun serviceNotifications(): Set<Pair<String, String>>
}
