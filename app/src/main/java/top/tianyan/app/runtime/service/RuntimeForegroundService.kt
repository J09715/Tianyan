package top.tianyan.app.runtime.service

import org.koin.android.ext.android.inject
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import top.tianyan.app.R
import top.tianyan.app.lifecycle.ProcessingPowerLease
import top.tianyan.app.lifecycle.RuntimeLifecycleSupervisor
import top.tianyan.app.runtime.shell.ProcessRegistry
import top.tianyan.app.runtime.SshServiceManager
import top.tianyan.app.runtime.FtpServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import top.tianyan.app.core.tools.PersistentToolServices

class RuntimeForegroundService : Service() {
    val processRegistry: ProcessRegistry by inject()
    val localServiceLauncher: LocalServiceLauncher by inject()
    val sshServiceManager: SshServiceManager by inject()
    val ftpServiceManager: FtpServiceManager by inject()
    val lifeCycleSupervisor: RuntimeLifecycleSupervisor by inject()
    private val persistentServices: PersistentToolServices by inject()
    private var recoveryJob: Job? = null
    /** 停止后的沙箱进程清理作用域：独立于服务生命周期，服务销毁后也要跑完。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 本服务在 RuntimeLifecycleSupervisor 中持有的租约。
     * 由 Supervisor 统一管理 WakeLock + WifiLock，此服务不再自持锁。
     */
    private var powerLease: ProcessingPowerLease? = null

    override fun onCreate() {
        super.onCreate()
        sshServiceManager.startObserving()
        ftpServiceManager.startObserving()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tianyan_runtime_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "用于展示 Linux 沙箱后台运行状态的常驻通知"
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        // 清理历史版本遗留的灵动岛/胶囊渠道，避免系统设置里残留无效项
        runCatching { manager.deleteNotificationChannel(LEGACY_CAPSULE_CHANNEL_ID) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Android 15+ 要求收到停止请求后在数秒时限内退出前台态，超时直接抛
            // ForegroundServiceDidNotStopInTimeException 杀进程。杀沙箱进程可能超过该
            // 时限，因此必须先同步退出前台，进程清理放到独立作用域异步完成。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            cleanupScope.launch {
                recoveryJob?.cancelAndJoin()
                runCatching { persistentServices.disableAll() }.onFailure { Log.w(TAG, "关闭服务自启动失败", it) }
                runCatching { localServiceLauncher.stopAll() }
                runCatching { processRegistry.stopAll() }
                runCatching { ftpServiceManager.stop() }
                runCatching { sshServiceManager.stop() }
                releaseLease()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        acquireLease()
        if (recoveryJob?.isActive != true) recoveryJob = cleanupScope.launch {
            while (true) {
                try { lifeCycleSupervisor.refreshActiveLeases(); persistentServices.check() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Log.w(TAG, "服务恢复检查失败", failure) }
                delay(5_000)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        // Defensive stop for a platform timeout; persistent runtime hosting declares specialUse.
        recoveryJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recoveryJob?.cancel()
        releaseLease()
        cleanupScope.launch {
            runCatching { ftpServiceManager.stop() }
            runCatching { sshServiceManager.stop() }
        }
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

    private fun notification(): Notification {
        val stopPending = PendingIntent.getService(
            this,
            1002,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.tianyan_notification)
            .setContentTitle("Linux 沙箱")
            .setContentText(getString(R.string.tianyan_runtime_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.tianyan_notification,
                    getString(R.string.tianyan_notification_stop),
                    stopPending,
                ),
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "top.tianyan.app.action.STOP_RUNTIME_SERVICE"
        private const val CHANNEL_ID = "tianyan-runtime-v5"
        private const val LEGACY_CAPSULE_CHANNEL_ID = "tianyan-runtime-capsule-v4"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RuntimeForegroundService"
        private const val LEASE_HOLDER_ID = "runtime-foreground-service"
    }
}
