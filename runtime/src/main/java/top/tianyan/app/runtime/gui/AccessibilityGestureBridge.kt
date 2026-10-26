package top.tianyan.app.runtime.gui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Optional global-gesture backend. Active only when the user enables
 * [TianyanGuiAccessibilityService] in system accessibility settings.
 */
object AccessibilityGestureBridge {
    @Volatile
    private var serviceRef: WeakReference<TianyanGuiAccessibilityService>? = null

    internal fun attach(service: TianyanGuiAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    internal fun detach(service: TianyanGuiAccessibilityService) {
        if (serviceRef?.get() === service) serviceRef = null
    }

    fun isAvailable(): Boolean = serviceRef?.get() != null

    fun tap(x: Int, y: Int, durationMs: Long = 50L): Boolean =
        dispatch(stroke(x, y, x, y, durationMs.coerceIn(1L, 5_000L)))

    fun longPress(x: Int, y: Int, durationMs: Long = 800L): Boolean =
        dispatch(stroke(x, y, x, y, durationMs.coerceIn(200L, 5_000L)))

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean =
        dispatch(stroke(x1, y1, x2, y2, durationMs.coerceIn(50L, 5_000L)))

    fun doubleTap(x: Int, y: Int, gapMs: Long = 80L): Boolean {
        if (!tap(x, y, 40L)) return false
        Thread.sleep(gapMs.coerceIn(40L, 400L))
        return tap(x, y, 40L)
    }

    private fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun dispatch(gesture: GestureDescription): Boolean {
        val service = serviceRef?.get() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val posted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok.set(false)
                    done.countDown()
                }
            },
            null,
        )
        if (!posted) return false
        done.await(3, TimeUnit.SECONDS)
        return ok.get()
    }
}

/**
 * 最近的窗口状态变化时间戳（uptimeMillis），用作"界面是否已经变了"的轻量信号。
 * 复用 service 已订阅的 typeWindowStateChanged，不额外订阅高频的 ContentChanged
 * （滚动时事件量极大，会带来功耗与卡顿），也不抓取任何节点。
 */
internal object UiSettleSignal {
    @Volatile
    private var lastEventAt = 0L

    fun mark() {
        lastEventAt = SystemClock.uptimeMillis()
    }

    fun lastEventAt(): Long = lastEventAt

    fun hasSignal(): Boolean = lastEventAt > 0L
}

/**
 * Thin accessibility service used only as a global gesture injector.
 * Does not scrape UI; screen observation still uses privileged uiautomator dump.
 * 事件回调仅记录时间戳，供 [HostGuiController.awaitUiSettled] 判断界面稳定。
 */
class TianyanGuiAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        AccessibilityGestureBridge.attach(this)
        Log.i(TAG, "GUI accessibility gesture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        UiSettleSignal.mark()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityGestureBridge.detach(this)
        if (instance?.get() === this) instance = null
        super.onDestroy()
        Log.i(TAG, "GUI accessibility gesture service destroyed")
    }

    companion object {
        private const val TAG = "Tianyan-GuiA11y"
        @Volatile
        private var instance: WeakReference<TianyanGuiAccessibilityService>? = null

        fun performGlobal(action: Int): Boolean {
            val service = instance?.get() ?: return false
            return runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        }

        /**
         * 对当前焦点输入框执行 ACTION_SET_TEXT，返回是否被目标控件接受。
         * 与 input text 不同，它直接设值而非合成按键事件，因此支持任意 Unicode（中文/emoji），
         * 也不依赖剪贴板与键盘快捷键。
         */
        fun setFocusedText(text: String): Boolean {
            val service = instance?.get() ?: return false
            return runCatching {
                val focused = service.rootInActiveWindow
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return@runCatching false
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        }

        /** 读回当前焦点输入框的文本，用于校验文本是否真的写入；读不到返回 null */
        fun focusedText(): String? {
            val service = instance?.get() ?: return null
            return runCatching {
                service.rootInActiveWindow
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.text
                    ?.toString()
            }.getOrNull()
        }
    }
}

/** Map scroll direction to a swipe across the current display. */
fun ScrollDirection.toSwipe(
    width: Int,
    height: Int,
    distanceRatio: Float,
    durationMs: Long,
    anchorX: Int?,
    anchorY: Int?,
): GuiPrimitive.Swipe {
    val cx = (anchorX ?: (width / 2)).coerceIn(0, width.coerceAtLeast(1))
    val cy = (anchorY ?: (height / 2)).coerceIn(0, height.coerceAtLeast(1))
    val travel = (minOf(width, height) * distanceRatio.coerceIn(0.15f, 0.8f)).roundToInt()
    return when (this) {
        ScrollDirection.UP -> GuiPrimitive.Swipe(cx, cy + travel / 2, cx, cy - travel / 2, durationMs)
        ScrollDirection.DOWN -> GuiPrimitive.Swipe(cx, cy - travel / 2, cx, cy + travel / 2, durationMs)
        ScrollDirection.LEFT -> GuiPrimitive.Swipe(cx + travel / 2, cy, cx - travel / 2, cy, durationMs)
        ScrollDirection.RIGHT -> GuiPrimitive.Swipe(cx - travel / 2, cy, cx + travel / 2, cy, durationMs)
    }
}
