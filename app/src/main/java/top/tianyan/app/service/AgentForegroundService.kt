package top.tianyan.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import top.tianyan.app.R
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.groupchat.GroupChatDispatcher
import top.tianyan.app.harness.subagent.BackgroundSubagentRegistry
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.lifecycle.ProcessingPowerLease
import top.tianyan.app.lifecycle.RuntimeLifecycleSupervisor
import org.koin.android.ext.android.getKoin

/** One stable foreground summary plus tagged per-session progress; no dependency on UI focus. */
class AgentForegroundService : Service() {
    val harnessLoop: SessionControl by inject()
    val sessionDao: HarnessSessionRepository by inject()
    val lifeCycleSupervisor: RuntimeLifecycleSupervisor by inject()
    val groupDispatcher: GroupChatDispatcher? get() = runCatching { getKoin().getOrNull<GroupChatDispatcher>() }.getOrNull()
    // 后台子代理批次不进 sessionRunStates：FGS 的 idle-stop 与电租约判定必须显式感知它，
    // 否则主轮结束后宽限期一到就把服务停掉，后台批次随进程失去保活。
    private val backgroundSubagents: BackgroundSubagentRegistry? get() = runCatching { getKoin().getOrNull<BackgroundSubagentRegistry>() }.getOrNull()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifications by lazy { AgentNotifications(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var collecting = false
    private var lease: ProcessingPowerLease? = null
    private var refresh: Job? = null
    private var idleStop: Job? = null
    private val started = linkedMapOf<String, Long>()
    private val titles = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(AgentNotifications.CHANNEL_ID,
            getString(R.string.tianyan_agent_notification_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = "展示每个任务的执行阶段、工具步骤和已用时"
            enableVibration(false)
            setSound(null, null)
        })
        manager.deleteNotificationChannel("tianyan-agent-capsule-v4")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val target = intent.getStringExtra(EXTRA_SESSION_ID)
            if (target != null) {
                harnessLoop.cancel(target)
                scope.launch { groupDispatcher?.cancelForSession(target) }
            } else {
                val targets = harnessLoop.sessionRunStates.value.filterValues { it in NOTIFIABLE_AGENT_STATES }.keys
                targets.forEach { harnessLoop.cancel(it) }
                scope.launch { groupDispatcher?.cancelAll() }
            }
            return START_NOT_STICKY
        }
        // Starting can precede the run-state emission (including RemoteInput). Allow admission to settle.
        if (!collecting) {
            runCatching { startForeground(AgentNotifications.SUMMARY_ID,
                notifications.running(null, "天衍任务", "正在准备执行…", System.currentTimeMillis())) }
                .onFailure { Log.w(TAG, "启动 Agent 前台通知失败", it) }
            collecting = true
            scope.launch {
                combine(harnessLoop.sessionRunStates, harnessLoop.sessionStatuses) { states, statuses -> states to statuses }
                    .conflate().collect { (states, statuses) -> reconcile(states, statuses) }
            }
            refresh = scope.launch {
                while (isActive) {
                    delay(2_000)
                    if (started.isNotEmpty()) render(harnessLoop.sessionRunStates.value, harnessLoop.sessionStatuses.value)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun reconcile(states: Map<String, SessionRunState>, statuses: Map<String, String>) {
        val active = states.filterValues { it in NOTIFIABLE_AGENT_STATES }.keys
        val ended = started.keys.toSet() - active
        ended.forEach { id ->
            val state = states[id]
            if (state == null) manager.cancel(id, AgentNotifications.SESSION_ID)
            else publish(id, AgentNotifications.SESSION_ID,
                notifications.settled(id, titles[id].orEmpty(), state, statuses[id]))
            started.remove(id)
            titles.remove(id)
        }
        active.forEach { id ->
            if (id !in started) {
                started[id] = System.currentTimeMillis()
                titles[id] = runCatching { sessionDao.findById(id)?.title }.getOrNull()
                    ?: getString(R.string.tianyan_agent_default_title)
            }
        }
        if (active.isNotEmpty()) {
            idleStop?.cancel()
            idleStop = null
            if (states.values.any { it == SessionRunState.RUNNING } || backgroundSubagents?.hasActive == true) {
                if (lease == null) lease = lifeCycleSupervisor.acquireLease("agent")
            } else releaseLease()
            render(states, statuses)
        } else {
            releaseLease()
            // Avoid killing a service launched immediately before a queued run starts.
            if (idleStop?.isActive != true) idleStop = scope.launch {
                delay(START_GRACE_MS)
                if (harnessLoop.sessionRunStates.value.values.none { it in NOTIFIABLE_AGENT_STATES } &&
                    backgroundSubagents?.hasActive != true
                ) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun render(states: Map<String, SessionRunState>, statuses: Map<String, String>) {
        val lines = started.keys.mapNotNull { id ->
            val state = states[id]?.takeIf { it in NOTIFIABLE_AGENT_STATES } ?: return@mapNotNull null
            val text = agentNotificationProgress(state, statuses[id], harnessLoop.messagesForSession(id).value)
            val elapsed = ((System.currentTimeMillis() - started.getValue(id)) / 1000).coerceAtLeast(0)
            val progress = "已用时 ${elapsed / 60}分${elapsed % 60}秒 · $text"
            publish(id, AgentNotifications.SESSION_ID,
                notifications.running(id, titles[id].orEmpty(), progress, started.getValue(id), state == SessionRunState.WAITING_APPROVAL))
            "${titles[id]}：$text"
        }
        if (lines.isEmpty()) return
        val running = started.keys.count { states[it] == SessionRunState.RUNNING }
        val waiting = started.keys.count { states[it] == SessionRunState.WAITING_APPROVAL }
        publish(null, AgentNotifications.SUMMARY_ID, notifications.running(null,
            "天衍 · $running 个执行中 / $waiting 个等待批准", lines.take(6).joinToString("\n\n"),
            started.values.minOrNull() ?: System.currentTimeMillis(), running == 0))
    }

    private fun publish(tag: String?, id: Int, notification: Notification) {
        runCatching { manager.notify(tag, id, notification) }
            .onFailure { Log.w(TAG, "发布 Agent 进度通知失败", it) }
    }
    private fun releaseLease() { lease?.close(); lease = null }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        releaseLease()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "top.tianyan.app.action.AGENT_START"
        const val ACTION_STOP = "top.tianyan.app.action.AGENT_STOP"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val KEY_REPLY = "agent_reply"
        internal const val START_GRACE_MS = 5_000L
        private const val TAG = "AgentForegroundService"

        fun start(context: Context, sessionId: String? = null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "通知权限未授权，请在权限引导中开启任务通知")
                return
            }
            runCatching { context.startForegroundService(Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, sessionId)) }
                .onFailure { Log.w(TAG, "启动 Agent 前台服务失败", it) }
        }
        fun startFromReply(context: Context, sessionId: String? = null) = start(context, sessionId)
    }
}
