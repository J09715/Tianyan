package top.tianyan.app.runtime.virtualdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ai.assistance.showerclient.ShowerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 虚拟屏悬浮窗（可视化层，Operit VirtualDisplayOverlay 的轻量替代）。
 *
 * 设计取舍：
 * - WindowManager 单例悬浮窗而非前台 Service：无通知、无 FGS 类型约束，
 *   进程存活期间持续显示，隐藏即回收；
 * - 视频用 TextureView 画在窗口里面。SurfaceView 在半透明悬浮窗下会沉到窗口背后，
 *   洞穿失败时整块是黑的；TextureView 跟普通 View 一样合成，画面能直接看见；
 * - 触摸回传：手指在悬浮窗上的操作按「视图坐标 → 视频坐标」等比映射后
 *   经 Binder 注入虚拟屏，用于人工干预 AI 的自动化过程；
 * - 纯代码构建 View（runtime 模块有 resourcePrefix 约束，避免引入布局资源）；
 *   配色克制：深灰底 + 白字，不引入主题色。
 */
object VirtualDisplayHud {

    private const val TAG = "VirtualDisplayHud"

    /** 预览宽度占屏幕宽度的比例。300dp 在手机上接近整屏宽，再按 19.5:9 拉高就会盖住大半个屏幕。 */
    private const val WINDOW_WIDTH_FRACTION = 0.40f
    /** 预览高度上限占屏幕高度的比例，避免竖屏画面把悬浮窗拉得比屏幕还高。 */
    private const val WINDOW_MAX_HEIGHT_FRACTION = 0.42f
    /** 标题条高度（dp）。 */
    private const val TITLE_BAR_HEIGHT_DP = 36
    /** 圆角半径（px），与天衍深色卡片风格一致的轻圆角。 */
    private const val CORNER_RADIUS_PX = 28

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 挂载代际计数：show 的 attach 经 mainHandler 异步执行，若期间发生 hide
     * （或新一轮 show），迟到的 attach 必须放弃挂载，否则会出现"已 hide 但窗口
     * 又弹回来"的竞态。
     */
    private val generation = AtomicLong()
    private var rootView: View? = null
    private var windowManager: WindowManager? = null
    private var scope: CoroutineScope? = null

    /** 当前展示的会话 ID（null = 未显示）。 */
    @Volatile
    var showingSessionId: String? = null
        private set

    /** 标题栏上的当前步骤。窗口还没挂上时先记着，挂上后写进去。 */
    private var stepText: String = "等待操作"
    private var statusView: TextView? = null

    /** 把当前做到哪一步写到悬浮窗标题上，用户不用翻聊天记录也能看到。 */
    fun setStep(step: String) {
        val text = step.trim().ifBlank { "等待操作" }
        stepText = text
        mainHandler.post { statusView?.text = titleFor(showingSessionId, text) }
    }

    private fun titleFor(sessionId: String?, step: String): String =
        "虚拟屏 · ${sessionId.orEmpty()} · $step"

    /**
     * 显示指定会话的虚拟屏悬浮窗。
     * 调用前需确保会话已建屏（[VirtualDisplayCoordinator.ensureVirtualDisplay]）。
     */
    @Synchronized
    fun show(context: Context, sessionId: String, coordinator: VirtualDisplayCoordinator) {
        if (showingSessionId == sessionId && rootView != null) {
            ShowerLog.d(TAG, "show: 悬浮窗已在展示 session=$sessionId")
            return
        }
        hide()
        val gen = generation.incrementAndGet()
        val appContext = context.applicationContext
        mainHandler.post {
            if (generation.get() != gen) {
                ShowerLog.d(TAG, "show: 丢弃迟到的挂载请求（已被 hide 或新一轮 show 取代）")
                return@post
            }
            runCatching { attach(appContext, sessionId, coordinator) }
                .onFailure { ShowerLog.e(TAG, "show: 悬浮窗挂载失败", it) }
        }
    }

    /** 隐藏并释放悬浮窗。 */
    @Synchronized
    fun hide() {
        generation.incrementAndGet()
        showingSessionId = null
        statusView = null
        scope?.cancel()
        scope = null
        val view = rootView ?: return
        rootView = null
        mainHandler.post {
            runCatching {
                windowManager?.removeView(view)
            }.onFailure { ShowerLog.w(TAG, "hide: 移除悬浮窗失败（可能已随窗口销毁）: ${it.message}") }
            windowManager = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(context: Context, sessionId: String, coordinator: VirtualDisplayCoordinator) {
        val controller = coordinator.controller(sessionId)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val screen = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(screen)
        val px = { dp: Int -> (dp * screen.density).toInt() }

        // ---- 根容器：深灰圆角卡片 ----
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1C1C1E"))
                cornerRadius = CORNER_RADIUS_PX.toFloat()
            }
        }

        // ---- 标题条：拖动区 + 会话名 + 关闭按钮 ----
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(4), px(4), px(4))
        }
        val title = TextView(context).apply {
            text = titleFor(sessionId, stepText)
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusView = title
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            setPadding(px(12), px(4), px(12), px(4))
            contentDescription = "停止任务并隐藏虚拟屏"
            setOnClickListener { coordinator.phoneTasks.cancel(sessionId); hide() }
        }
        titleBar.addView(title)
        titleBar.addView(closeButton)

        // 标题条拖动悬浮窗
        val windowParamsRef = arrayOfNulls<WindowManager.LayoutParams>(1)
        var downRawX = 0f
        var downRawY = 0f
        var startWindowX = 0
        var startWindowY = 0
        titleBar.setOnTouchListener { _, event ->
            val params = windowParamsRef[0]
            when (event.actionMasked) {
                // 必须消费 DOWN，否则本 View 事件流终止，后续 MOVE 收不到、拖动失效
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = params?.x ?: 0
                    startWindowY = params?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null) {
                        params.x = startWindowX + (event.rawX - downRawX).toInt()
                        params.y = startWindowY + (event.rawY - downRawY).toInt()
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                else -> false
            }
        }

        val maxVideoW = (screen.widthPixels * WINDOW_WIDTH_FRACTION).toInt().coerceAtLeast(px(148))
        val maxVideoH = (screen.heightPixels * WINDOW_MAX_HEIGHT_FRACTION).toInt().coerceAtLeast(px(220))

        // ---- 视频区：TextureView 解码 + 触摸回传 ----
        val videoView = TouchForwardVideoView(context).apply {
            forwardController = controller
            forwardScopeSupplier = { scope }
            onManualTouch = {
                coordinator.phoneTasks.pause(sessionId, manual = true)
                setStep("人工操作中 · 点继续后重新观察")
            }
        }

        root.addView(
            titleBar,
            LinearLayout.LayoutParams(maxVideoW, px(TITLE_BAR_HEIGHT_DP)),
        )
        root.addView(
            videoView,
            LinearLayout.LayoutParams(maxVideoW, maxVideoH),
        )
        val controls = phoneTaskHudControls(context, coordinator.phoneTasks, sessionId, scope!!)
        root.addView(controls, LinearLayout.LayoutParams(maxVideoW, px(40)))

        // video size 就绪后按实际宽高比收进最大宽高，避免竖屏把窗口拉满。
        scope?.launch {
            var size = controller.getVideoSize()
            var tries = 0
            while (size == null && tries < 50) {
                delay(100)
                tries++
                size = controller.getVideoSize()
            }
            val (vw, vh) = size ?: return@launch
            if (vw <= 0 || vh <= 0 || root.parent == null) return@launch
            var targetW = maxVideoW
            var targetH = targetW * vh / vw
            if (targetH > maxVideoH) {
                targetH = maxVideoH
                targetW = targetH * vw / vh
            }
            mainHandler.post {
                runCatching {
                    videoView.layoutParams = (videoView.layoutParams as LinearLayout.LayoutParams).apply {
                        width = targetW
                        height = targetH
                    }
                    titleBar.layoutParams = (titleBar.layoutParams as LinearLayout.LayoutParams).apply {
                        width = targetW
                    }
                    controls.layoutParams = (controls.layoutParams as LinearLayout.LayoutParams).apply {
                        width = targetW
                    }
                    videoView.requestLayout()
                }
            }
        }

        // ---- WindowManager 参数：应用上层、不抢焦点、可触摸 ----
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(24)
            y = px(96)
        }
        windowParamsRef[0] = params

        // 先挂载成功再记录状态：addView 失败时保持 rootView/windowManager 为空，
        // 保证后续 hide() 的清理语义一致。
        wm.addView(root, params)
        windowManager = wm
        rootView = root
        showingSessionId = sessionId
        ShowerLog.d(TAG, "attach: 悬浮窗已挂载 session=$sessionId")
    }
}
