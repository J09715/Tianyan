package top.tianyan.app.runtime.webchat

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** 天衍智枢 Web 协作台的前台通知（从 WebChatBridgeServer 抽出，保持服务观感不变）。 */
internal class WebBridgeNotification(private val context: Context) {

    fun show(url: String, pin: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "天衍智枢 Web 协作台",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "天衍智枢局域网协作服务"; setShowBadge(false) },
            )
        }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.stat_notify_sync)
                .setContentTitle("天衍智枢 Web 协作台运行中")
                .setContentText("$url（配对码：$pin）")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    fun hide() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "tianyan_webchat_bridge"
        const val NOTIFICATION_ID = 8899
    }
}
