package top.tianyan.app.workflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.R
import top.tianyan.app.MainActivity
import top.tianyan.app.service.WorkflowForegroundService

/**
 * 后台审批通知：App 在后台时出现待审批工作流节点，发 IMPORTANCE_HIGH 通知
 * （带 批准/拒绝 按钮），点正文打开运行页。回到前台（或审批已处理）后自动撤掉通知。
 */
class WorkflowApprovalNotifier(
    private val runManager: WorkflowRunManager,
    private val foregroundTracker: AppForegroundTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifiedKeys = mutableSetOf<String>()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            combine(runManager.pendingApprovals, foregroundTracker.inForeground) { approvals, foreground ->
                approvals to foreground
            }.collect { (approvals, foreground) ->
                if (foreground) {
                    // 前台时页面弹审批对话框，撤掉所有审批通知避免双份打扰；
                    // 同时清掉已发布标记，回后台时同一审批要能重新发布
                    approvals.keys.forEach { revoke(appContext, it) }
                    notifiedKeys.clear()
                    return@collect
                }
                approvals.forEach { (executionId, request) ->
                    val key = "$executionId:${request.nodeId}"
                    if (notifiedKeys.add(key)) {
                        notifyApproval(appContext, executionId, request.nodeId, request.title, request.reviewToken != null)
                    }
                }
                // 已处理的审批撤通知：遍历已发布标记（快照，避免边遍历边删），
                // executionId 已不在 pending 集合中的说明审批已被处理，撤销其通知。
                // 注意不能遍历 approvals.keys——那里面全是仍待审批的 execution，
                // 旧实现导致撤销分支恒不可达，通知处理完仍常驻。
                notifiedKeys.toList()
                    .forEach { key ->
                        val executionId = key.substringBefore(':')
                        if (executionId !in approvals.keys) {
                            notifiedKeys.remove(key)
                            revoke(appContext, executionId)
                        }
                    }
            }
        }
    }

    private fun notifyApproval(context: Context, executionId: String, nodeId: String, title: String, requiresReview: Boolean) {
        if (!hasNotificationPermission(context)) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "工作流审批", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "工作流执行到人工审批节点时的提醒"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val approvePending = PendingIntent.getBroadcast(
                context,
                decisionRequestCode(executionId, nodeId, approve = true),
                Intent(context, WorkflowApprovalReceiver::class.java)
                    .setAction(WorkflowApprovalReceiver.ACTION_DECIDE)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_EXECUTION_ID, executionId)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_NODE_ID, nodeId)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_APPROVED, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val denyPending = PendingIntent.getBroadcast(
                context,
                decisionRequestCode(executionId, nodeId, approve = false),
                Intent(context, WorkflowApprovalReceiver::class.java)
                    .setAction(WorkflowApprovalReceiver.ACTION_DECIDE)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_EXECUTION_ID, executionId)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_NODE_ID, nodeId)
                    .putExtra(WorkflowApprovalReceiver.EXTRA_APPROVED, false),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val openRun = PendingIntent.getActivity(
                context,
                executionId.hashCode().absoluteValue,
                Intent(context, MainActivity::class.java)
                    .setAction(WorkflowForegroundService.ACTION_OPEN_RUN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(WorkflowForegroundService.EXTRA_EXECUTION_ID, executionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.tianyan_notification)
                .setContentTitle("工作流等待审批")
                .setContentText(title.ifBlank { "节点 $nodeId 需要你批准后继续执行" })
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(title.ifBlank { "节点 $nodeId 需要你批准后继续执行" }),
                )
                .setContentIntent(openRun)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(NotificationCompat.Action(R.drawable.tianyan_notification, if (requiresReview) "查看提案" else "批准", if (requiresReview) openRun else approvePending))
                .addAction(NotificationCompat.Action(R.drawable.tianyan_notification, "拒绝", denyPending))
                .build()
            manager.notify(notificationId(executionId), notification)
        }.onFailure { Log.w(TAG, "发布审批通知失败", it) }
    }

    private fun revoke(context: Context, executionId: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(executionId))
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(executionId: String): Int =
        NOTIFICATION_ID_BASE + (executionId.hashCode().absoluteValue % 9000)

    private fun decisionRequestCode(executionId: String, nodeId: String, approve: Boolean): Int =
        (if (approve) 1 else 2) * 10_000_000 +
            (executionId.hashCode().absoluteValue % 5_000_000) +
            (nodeId.hashCode().absoluteValue % 1_000_000)

    private companion object {
        const val CHANNEL_ID = "tianyan-workflow-approval"
        const val NOTIFICATION_ID_BASE = 13_001
        const val TAG = "WorkflowApprovalNotif"
    }
}
