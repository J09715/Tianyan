package top.tianyan.app.service

import org.koin.android.ext.android.inject
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import top.tianyan.app.R
import top.tianyan.app.lifecycle.ProcessingPowerLease
import top.tianyan.app.lifecycle.RuntimeLifecycleSupervisor

/**
 * MCP 被控端常驻前台服务。
 *
 * 本服务**不持有 MCP server** —— 服务端生命周期仍由 harness 的 `AgentMcpBootstrap` 依偏好驱动；
 * 这里只做两件事：把进程提升为前台（常驻通知）+ 向 [RuntimeLifecycleSupervisor] 申请电源租约，
 * 避免 App 退到后台后被系统冻结/息屏休眠，导致外部 AI 客户端的连接被静默掐断。
 *
 * 前台类型用 `specialUse` 而非 `dataSync`：dataSync 在 Android 15+ 有 6 小时硬超时，
 * 会周期性掐断外部客户端；被控端要的是"用户开着就一直可用"。
 */
class AgentMcpForegroundService : Service() {

    val lifeCycleSupervisor: RuntimeLifecycleSupervisor by inject()

    /** 本服务在 [RuntimeLifecycleSupervisor] 中持有的租约；WakeLock/WifiLock 由 Supervisor 统一管理。 */
    private var powerLease: ProcessingPowerLease? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tianyan_agent_mcp_notification_channel),
                    // 常驻状态指示而非告警：LOW 静默展示，不触发横幅与提示音
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.tianyan_agent_mcp_running_hint)
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }.onFailure { Log.w(TAG, "创建通知渠道失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, notification()) }
            .onFailure { Log.w(TAG, "startForeground 失败", it) }
        acquireLease()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLease()
        super.onDestroy()
    }

    /** 向 [RuntimeLifecycleSupervisor] 申请租约（幂等，已持有时跳过）。 */
    private fun acquireLease() {
        if (powerLease != null) return
        powerLease = lifeCycleSupervisor.acquireLease(LEASE_HOLDER_ID)
        Log.i(TAG, "Acquired power lease from RuntimeLifecycleSupervisor")
    }

    /** 释放租约；幂等，安全多次调用。 */
    private fun releaseLease() {
        powerLease?.close()
        powerLease = null
        Log.i(TAG, "Released power lease from RuntimeLifecycleSupervisor")
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.tianyan_notification)
            .setContentTitle(getString(R.string.tianyan_agent_mcp_notification_channel))
            .setContentText(getString(R.string.tianyan_agent_mcp_running_hint))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "tianyan-agent-mcp-v1"
        private const val NOTIFICATION_ID = 3001
        private const val TAG = "AgentMcpFgService"
        private const val LEASE_HOLDER_ID = "agent-mcp-server"

        /**
         * 拉起常驻服务。通知权限缺失时静默跳过：MCP 被控端仍可用，
         * 只是 App 退到后台后可能被系统冻结（与用户未授权通知的取舍一致）。
         */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "通知权限未授权，跳过 MCP 被控端前台保活服务")
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentMcpForegroundService::class.java),
                )
            }.onFailure { Log.w(TAG, "启动 MCP 被控端前台服务失败", it) }
        }

        /** 停止常驻服务。用 stopService 而非 startService+ACTION：后台态下也可安全调用。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentMcpForegroundService::class.java)) }
                .onFailure { Log.w(TAG, "停止 MCP 被控端前台服务失败", it) }
        }
    }
}
