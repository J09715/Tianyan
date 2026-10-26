package top.tianyan.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import top.tianyan.app.MainActivity
import top.tianyan.app.R
import top.tianyan.app.core.model.SessionRunState

internal class AgentNotifications(private val context: Context) {
    private fun openApp(sessionId: String?) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(identity(sessionId, "open"))
            .putExtra(AgentForegroundService.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun identity(sessionId: String?, action: String): Uri = Uri.Builder()
        .scheme("tianyan").authority("agent-notification").appendPath(sessionId ?: "all").appendPath(action).build()

    private fun base(title: String, text: String, sessionId: String?) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.tianyan_notification).setContentTitle(title).setContentText(text.lineSequence().first())
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openApp(sessionId)).setOnlyAlertOnce(true)
        .setGroup(GROUP_KEY).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    fun running(sessionId: String?, title: String, text: String, startedAt: Long, waiting: Boolean = false): Notification {
        val stop = PendingIntent.getService(context, 0,
            Intent(context, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_STOP)
                .setData(identity(sessionId, "stop")).putExtra(AgentForegroundService.EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return base(title, text, sessionId).setOngoing(true).setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(startedAt).setUsesChronometer(true).setProgress(0, 0, !waiting)
            .setGroupSummary(sessionId == null)
            .addAction(R.drawable.tianyan_notification, context.getString(R.string.tianyan_notification_stop), stop).build()
    }

    fun settled(sessionId: String, title: String, state: SessionRunState, status: String?, allowReply: Boolean = true): Notification {
        val outcome = when (state) {
            SessionRunState.COMPLETED -> "任务已完成"
            SessionRunState.FAILED -> "执行失败"
            else -> "已停止，结果待核对"
        }
        val builder = base("$title · $outcome", status?.take(240)?.takeIf { it.isNotBlank() } ?: outcome, sessionId)
            .setOngoing(false).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (allowReply) {
            val reply = PendingIntent.getBroadcast(context, 0,
                Intent(context, AgentReplyReceiver::class.java).setData(identity(sessionId, "reply"))
                    .putExtra(AgentForegroundService.EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(R.drawable.tianyan_notification,
                context.getString(R.string.tianyan_notification_reply), reply)
                .addRemoteInput(RemoteInput.Builder(AgentForegroundService.KEY_REPLY)
                    .setLabel(context.getString(R.string.tianyan_notification_reply_to, title)).build()).build())
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "tianyan-agent-v5"
        const val GROUP_KEY = "tianyan-agent-progress"
        const val SUMMARY_ID = 2001
        const val SESSION_ID = 2002
    }
}
