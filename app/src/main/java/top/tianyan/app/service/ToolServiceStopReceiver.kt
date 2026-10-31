package top.tianyan.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.core.tools.ToolServiceNotificationContract

/**
 * 网关服务通知栏【停止】按钮。
 *
 * 语义与工具中心的停止按钮一致：停掉服务并关闭自启动，避免通知消失后又被恢复循环拉起来。
 * `tools` 模块不能引用本模块的类，因此两边只共享 [ToolServiceNotificationContract] 的 action 常量。
 */
class ToolServiceStopReceiver : BroadcastReceiver(), KoinComponent {
    private val tools: ToolManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val toolId = intent.getStringExtra(ToolServiceNotificationContract.EXTRA_TOOL_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                tools.stopGateway(toolId)
            } catch (failure: Exception) {
                Log.w(TAG, "停止网关服务失败：$toolId", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ToolServiceStopReceiver"
    }
}
