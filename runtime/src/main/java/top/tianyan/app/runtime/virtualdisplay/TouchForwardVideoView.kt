package top.tianyan.app.runtime.virtualdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerLog
import com.ai.assistance.showerclient.ShowerVideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 用 TextureView 解码虚拟屏视频，并把触摸按视图→视频坐标回注。
 * SurfaceView 在 TYPE_APPLICATION_OVERLAY 里经常整块黑屏，TextureView 不会。
 */
internal class TouchForwardVideoView(context: Context) :
    TextureView(context),
    TextureView.SurfaceTextureListener {

    private val renderer = ShowerVideoRenderer()
    private var decodeSurface: Surface? = null
    private var attachJob: Job? = null

    var forwardController: ShowerController? = null
    var forwardScopeSupplier: (() -> CoroutineScope?)? = null
    var onManualTouch: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val ctrl = forwardController ?: return
        val scope = forwardScopeSupplier?.invoke() ?: return
        attachJob?.cancel()
        attachJob = scope.launch {
            var size = ctrl.getVideoSize()
            var tries = 0
            while (size == null && tries < 50) {
                delay(100)
                tries++
                size = ctrl.getVideoSize()
            }
            val (videoW, videoH) = size ?: run {
                ShowerLog.e(TAG, "TextureView: 等不到视频尺寸，画面会一直是黑的")
                return@launch
            }
            if (!isAvailable || videoW <= 0 || videoH <= 0) return@launch
            surface.setDefaultBufferSize(videoW, videoH)
            val output = Surface(surface)
            decodeSurface = output
            renderer.attach(output, videoW, videoH)
            ctrl.setBinaryHandler { frame -> renderer.onFrame(frame) }
            ShowerLog.d(TAG, "TextureView: 解码表面已接上 ${videoW}x$videoH")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        attachJob?.cancel()
        attachJob = null
        touchJob?.cancel()
        touchJob = null
        synchronized(touches) { touches.clear() }
        forwardController?.setBinaryHandler(null)
        renderer.detach()
        decodeSurface?.release()
        decodeSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private data class Touch(
        val controller: ShowerController, val action: Int, val x: Float, val y: Float,
        val downTime: Long, val eventTime: Long,
    )
    private val touches = ArrayDeque<Touch>()
    private val wakeTouch = Channel<Unit>(Channel.CONFLATED)
    private var touchJob: Job? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val controller = forwardController ?: return true
        val scope = forwardScopeSupplier?.invoke() ?: return true
        val (vw, vh) = controller.getVideoSize() ?: return true
        val action = event.actionMasked
        if (action !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return true
        if (action == MotionEvent.ACTION_DOWN) onManualTouch?.invoke()
        // MotionEvent 会被系统回收，必须在回调内复制时间和坐标。
        val touch = Touch(controller, action,
            (event.x * (vw - 1) / width.coerceAtLeast(1)).coerceIn(0f, (vw - 1).toFloat()),
            (event.y * (vh - 1) / height.coerceAtLeast(1)).coerceIn(0f, (vh - 1).toFloat()),
            event.downTime, event.eventTime)
        synchronized(touches) {
            if (action == MotionEvent.ACTION_MOVE && touches.lastOrNull()?.action == action) touches.removeLast()
            touches.addLast(touch)
        }
        if (touchJob?.isActive != true) touchJob = scope.launch { drainTouches() }
        wakeTouch.trySend(Unit)
        return true
    }

    private suspend fun drainTouches() {
        var pressed: Touch? = null
        try {
            for (signal in wakeTouch) {
                while (true) {
                    val touch = synchronized(touches) { touches.removeFirstOrNull() } ?: break
                    // 先登记触点，即使取消发生在 Binder 回程也会发送 CANCEL。
                    if (touch.action == MotionEvent.ACTION_DOWN) pressed = touch
                    inject(touch)
                    if (touch.action == MotionEvent.ACTION_UP || touch.action == MotionEvent.ACTION_CANCEL) pressed = null
                }
            }
        } finally {
            pressed?.let { touch ->
                withContext(NonCancellable) { inject(touch.copy(action = MotionEvent.ACTION_CANCEL)) }
            }
        }
    }

    private suspend fun inject(touch: Touch) {
        val accepted = touch.controller.injectTouchEvent(
            action = touch.action, x = touch.x, y = touch.y,
            downTime = touch.downTime, eventTime = touch.eventTime,
            pressure = if (touch.action == MotionEvent.ACTION_UP || touch.action == MotionEvent.ACTION_CANCEL) 0f else 1f,
            size = 1f, metaState = 0, xPrecision = 1f, yPrecision = 1f, deviceId = 0, edgeFlags = 0,
        )
        if (!accepted) ShowerLog.w(TAG, "触摸回传失败: action=${touch.action}")
    }
    private companion object {
        const val TAG = "VirtualDisplayHud"
    }
}
