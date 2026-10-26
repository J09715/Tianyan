package top.tianyan.app.ui.workflow.hud

import org.koin.android.ext.android.inject
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.tianyan.app.runtime.gui.WorkflowGuiHudBridge
import top.tianyan.app.ui.theme.TianyanTheme
import android.app.Activity

/**
 * Workflow run HUD: pill-shaped status + stop button. Only attached while the
 * app itself is in the background; also detaches from WindowManager while
 * [WorkflowGuiHudBridge.Session.overlayVisible] is false so screen dumps skip
 * the overlay.
 */
class WorkflowHudService : Service() {

    val hud: WorkflowGuiHudBridge by inject()

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: WorkflowHudLifecycleOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var attached = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** App 前后台状态：仅当应用退到后台时才显示悬浮窗。 */
    private var appInForeground = true
    private var startedActivities = 0
    private var lastSession: WorkflowGuiHudBridge.Session? = null
    private var dismissJob: Job? = null

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (!appInForeground) {
                appInForeground = true
                updateAttachment()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0 && appInForeground) {
                appInForeground = false
                updateAttachment()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }
        (applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * resources.displayMetrics.density).toInt()
        }
        windowParams = params

        val owner = WorkflowHudLifecycleOwner()
        lifecycleOwner = owner
        owner.onCreate()

        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            owner.attach(this)
            setContent {
                TianyanTheme {
                    val session by hud.session.collectAsState()
                    val current = session
                    if (current != null) {
                        WorkflowHudOverlay(
                            session = current,
                            onStop = { hud.requestStop() },
                            onDismiss = {
                                hud.dismiss()
                                stopSelf()
                            },
                        )
                    }
                }
            }
        }
        composeView = view

        serviceScope.launch {
            hud.session.collect { session ->
                lastSession = session
                if (session == null) {
                    stopSelf()
                    return@collect
                }
                if (session.active) {
                    dismissJob?.cancel()
                    dismissJob = null
                } else if (dismissJob == null) {
                    // 结束态 12 秒后自动关闭（不依赖悬浮窗是否可见）
                    dismissJob = launch {
                        delay(12_000)
                        val s = hud.session.value
                        if (s != null && !s.active && s.executionId == session.executionId) {
                            hud.dismiss()
                            stopSelf()
                        }
                    }
                }
                updateAttachment()
            }
        }
    }

    /** 悬浮窗挂载条件：应用在后台 && (结束态恒显 || 运行态未被屏幕操作临时摘除)。 */
    private fun updateAttachment() {
        val session = lastSession ?: return
        val shouldShow = !appInForeground && when {
            !session.active -> true
            else -> session.overlayVisible
        }
        if (shouldShow) attachOverlay() else detachOverlay()
    }

    private fun attachOverlay() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = windowParams ?: return
        if (attached) return
        runCatching {
            wm.addView(view, params)
            lifecycleOwner?.onStart()
            attached = true
        }
    }

    private fun detachOverlay() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        if (!attached) return
        runCatching {
            lifecycleOwner?.onStop()
            wm.removeView(view)
        }
        attached = false
    }

    override fun onDestroy() {
        super.onDestroy()
        (applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(activityCallbacks)
        detachOverlay()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        composeView = null
        windowManager = null
        serviceScope.cancel()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, WorkflowHudService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkflowHudService::class.java))
        }
    }
}
