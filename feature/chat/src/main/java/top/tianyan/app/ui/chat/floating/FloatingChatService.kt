package top.tianyan.app.ui.chat.floating

import org.koin.android.ext.android.inject
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.harness.session.InteractiveSessionControl
import top.tianyan.app.ui.theme.TianyanTheme
import kotlin.math.roundToInt

/**
 * 智枢 AI 桌面悬浮小窗后台服务。
 * 管理 WindowManager 智枢悬浮图层的创建、更新、对话发送与边缘自动吸附交互（纯净无底色遮罩）。
 */
class FloatingChatService : Service() {

    val harnessLoop: InteractiveSessionControl by inject()

    val sessionDao: HarnessSessionRepository by inject()

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: FloatingChatLifecycleOwner? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isExpanded by mutableStateOf(false)
    private var isEdgeHidden by mutableStateOf(false)
    private var dockedOnLeft by mutableStateOf(false)
    private var windowParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private var autoHideJob: Job? = null
    private var pendingLayoutListener: View.OnLayoutChangeListener? = null

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

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val initialX = (displayMetrics.widthPixels - 150 * density).roundToInt()
        val initialY = (displayMetrics.heightPixels * 0.20f).roundToInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            dimAmount = 0.0f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowParams = params

        val owner = FloatingChatLifecycleOwner()
        lifecycleOwner = owner
        owner.onCreate()

        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            owner.attach(this)
            setContent {
                TianyanTheme {
                    val messages by harnessLoop.messages.collectAsState()
                    val running by harnessLoop.running.collectAsState()
                    val thinkingLive by harnessLoop.thinkingLive.collectAsState()
                    val currentSessionId by harnessLoop.currentSessionId.collectAsState()
                    val sessions: List<HarnessSessionEntity> by sessionDao.observeAll().collectAsState(initial = emptyList())
                    val currentSessionTitle = sessions.firstOrNull { it.id == currentSessionId }?.title

                    FloatingChatView(
                        sessionTitle = currentSessionTitle,
                        messages = messages,
                        running = running,
                        thinkingLive = thinkingLive,
                        isExpanded = isExpanded,
                        isEdgeHidden = isEdgeHidden,
                        dockedOnLeft = dockedOnLeft,
                        onToggleExpanded = {
                            autoHideJob?.cancel()
                            val nextExpanded = !isExpanded
                            isExpanded = nextExpanded
                            isEdgeHidden = false
                            updateOverlaySize()
                            updateWindowFocusability(nextExpanded)
                            if (!nextExpanded) scheduleAutoHide()
                        },
                        onReveal = {
                            isEdgeHidden = false
                            updateOverlaySize()
                            snapToEdgeAfterLayout()
                            scheduleAutoHide()
                        },
                        onDragBy = { dx, dy ->
                            autoHideJob?.cancel()
                            snapAnimator?.cancel()
                            val currentParams = windowParams ?: return@FloatingChatView
                            currentParams.x += dx.roundToInt()
                            currentParams.y += dy.roundToInt()
                            runCatching { windowManager?.updateViewLayout(this@apply, currentParams) }
                        },
                        onDragEnd = {
                            if (isExpanded) {
                                snapToEdge()
                            } else {
                                val currentParams = windowParams
                                val width = composeView?.width?.takeIf { it > 0 }
                                    ?: ((if (isEdgeHidden) EDGE_HANDLE_TOUCH_WIDTH_DP else 150) * resources.displayMetrics.density).roundToInt()
                                if (currentParams != null) {
                                    dockedOnLeft = currentParams.x + width / 2 < resources.displayMetrics.widthPixels / 2
                                }
                                isEdgeHidden = true
                                updateOverlaySize()
                                snapToEdgeAfterLayout()
                            }
                        },
                        onSendPrompt = { prompt ->
                            serviceScope.launch {
                                harnessLoop.send(prompt)
                            }
                        },
                        onStopAgent = {
                            harnessLoop.cancel()
                        },
                        onRestoreApp = {
                            restoreAppToChat()
                            stopSelf()
                        },
                        onClose = {
                            stopSelf()
                        },
                    )
                }
            }
        }
        composeView = view

        runCatching {
            windowManager?.addView(view, params)
            owner.onStart()
            snapToEdgeAfterLayout()
            scheduleAutoHide()
        }.onFailure {
            stopSelf()
        }
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        if (isExpanded || isEdgeHidden) return
        autoHideJob = serviceScope.launch {
            delay(3_000)
            if (!isExpanded && !isEdgeHidden) {
                isEdgeHidden = true
                updateOverlaySize()
                snapToEdgeAfterLayout()
            }
        }
    }

    private fun updateOverlaySize() {
        val params = windowParams ?: return
        val view = composeView ?: return
        val density = resources.displayMetrics.density
        params.width = if (isEdgeHidden) (EDGE_HANDLE_TOUCH_WIDTH_DP * density).roundToInt()
            else WindowManager.LayoutParams.WRAP_CONTENT
        params.height = if (isEdgeHidden) (EDGE_HANDLE_TOUCH_HEIGHT_DP * density).roundToInt()
            else WindowManager.LayoutParams.WRAP_CONTENT
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun snapToEdgeAfterLayout() {
        val view = composeView ?: return
        snapToEdge()
        pendingLayoutListener?.let(view::removeOnLayoutChangeListener)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                changedView: View,
                left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                changedView.removeOnLayoutChangeListener(this)
                pendingLayoutListener = null
                snapToEdge()
            }
        }
        pendingLayoutListener = listener
        view.addOnLayoutChangeListener(listener)
    }

    /**
     * 边缘平滑吸附动效（松手后自动贴附屏幕最近边缘，面板态自动限定在安全视口内）。
     */
    private fun snapToEdge() {
        val currentParams = windowParams ?: return
        val currentView = composeView ?: return
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        snapAnimator?.cancel()

        val currentX = currentParams.x
        val currentY = currentParams.y

        val viewWidth = when {
            isEdgeHidden -> (EDGE_HANDLE_TOUCH_WIDTH_DP * density).roundToInt()
            isExpanded -> (310 * density).roundToInt()
            else -> currentView.width.takeIf { it > (EDGE_HANDLE_TOUCH_WIDTH_DP * density).roundToInt() }
                ?: (150 * density).roundToInt()
        }
        val viewHeight = when {
            isEdgeHidden -> (EDGE_HANDLE_TOUCH_HEIGHT_DP * density).roundToInt()
            isExpanded -> (410 * density).roundToInt()
            else -> currentView.height.takeIf { it > 0 } ?: (40 * density).roundToInt()
        }

        val targetX = if (isExpanded) {
            // 面板态：限制在屏幕安全视口内
            val margin = (8 * density).roundToInt()
            currentX.coerceIn(margin, (screenWidth - viewWidth - margin).coerceAtLeast(margin))
        } else {
            // 胶囊与边签态共用最近边缘；边签只露出 20dp 的小拉片。
            if (!isEdgeHidden) {
                val centerX = currentX + viewWidth / 2
                dockedOnLeft = centerX < screenWidth / 2
            }
            val margin = if (isEdgeHidden) 0 else (10 * density).roundToInt()
            if (dockedOnLeft) {
                margin
            } else {
                screenWidth - viewWidth - margin
            }
        }

        // Y 轴限制在顶部状态栏与底部安全区之间
        val minY = (40 * density).roundToInt()
        val maxY = (screenHeight - viewHeight - (60 * density)).roundToInt().coerceAtLeast(minY)
        val targetY = currentY.coerceIn(minY, maxY)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            val startX = currentX
            val startY = currentY
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                currentParams.x = (startX + (targetX - startX) * fraction).roundToInt()
                currentParams.y = (startY + (targetY - startY) * fraction).roundToInt()
                runCatching { windowManager?.updateViewLayout(currentView, currentParams) }
            }
        }
        snapAnimator = animator
        animator.start()
    }

    private fun updateWindowFocusability(expanded: Boolean) {
        val currentParams = windowParams ?: return
        val currentView = composeView ?: return
        if (expanded) {
            // 面板态：允许获取焦点与弹出软键盘输入法
            currentParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            currentParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            // 胶囊态：不拦截背景焦点
            currentParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        currentParams.dimAmount = 0.0f
        runCatching { windowManager?.updateViewLayout(currentView, currentParams) }
        snapToEdgeAfterLayout()
    }

    private fun restoreAppToChat() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAVIGATE_TO", "agent")
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        snapAnimator?.cancel()
        snapAnimator = null
        autoHideJob?.cancel()
        autoHideJob = null
        pendingLayoutListener?.let { listener ->
            composeView?.removeOnLayoutChangeListener(listener)
        }
        pendingLayoutListener = null

        lifecycleOwner?.onStop()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null

        composeView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        composeView = null
        windowManager = null
        serviceScope.cancel()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingChatService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingChatService::class.java)
            context.stopService(intent)
        }
    }
}
