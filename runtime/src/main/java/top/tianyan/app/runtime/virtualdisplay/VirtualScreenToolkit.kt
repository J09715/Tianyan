package top.tianyan.app.runtime.virtualdisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.gui.GuiClipboardLock
import top.tianyan.app.runtime.gui.GuiBackendId
import top.tianyan.app.runtime.gui.GuiExecResult
import top.tianyan.app.runtime.gui.GuiKey
import top.tianyan.app.runtime.gui.GuiPrimitive
import top.tianyan.app.runtime.gui.toSwipe

/**
 * 虚拟屏 GUI 原语执行器：复用主屏的 [GuiPrimitive] 体系，把动作定向到
 * [VirtualDisplayCoordinator] 管理的 Shower 虚拟屏。
 *
 * 与主屏 [top.tianyan.app.runtime.gui.HostGuiToolkit] 的差异：
 * - 虚拟屏输入走 shower-server 的 Binder 注入（InputManager.injectInputEvent +
 *   setDisplayId），无多后端降级链——server 端自带拟人化抖动，成功率即可用性；
 * - PasteText 通过主屏剪贴板（系统全局共享）+ KEYCODE_PASTE 注入，依赖目标
 *   应用响应 PASTE 键；虚拟屏无无障碍节点树，不支持 ACTION_SET_TEXT 直设。
 */
class VirtualScreenToolkit(
    private val context: Context,
    private val coordinator: VirtualDisplayCoordinator,
) {

    suspend fun execute(sessionId: String, action: GuiPrimitive, canInject: () -> Boolean = { true }): GuiExecResult =
        withContext(Dispatchers.IO) {
            val backend = GuiBackendId.SHOWER_VIRTUAL_DISPLAY
            val controller = coordinator.controller(sessionId)
            if (!canInject()) return@withContext GuiExecResult(false, "任务已暂停或画面已失效", backend)
            if (coordinator.getDisplayId(sessionId) == null) {
                return@withContext GuiExecResult(
                    false,
                    "虚拟屏未创建（session=$sessionId）：请先调用 virtual_screen_ensure",
                    backend,
                )
            }
            when (action) {
                is GuiPrimitive.Tap -> {
                    if (controller.tap(action.x, action.y)) {
                        GuiExecResult(true, "已在虚拟屏点击 (${action.x},${action.y})", backend)
                    } else {
                        GuiExecResult(false, "虚拟屏点击失败 (${action.x},${action.y})", backend)
                    }
                }

                is GuiPrimitive.DoubleTap -> {
                    val first = controller.tap(action.x, action.y)
                    delay(action.gapMs.coerceIn(40L, 400L))
                    if (!canInject()) return@withContext GuiExecResult(false, "双击已中止：任务暂停或画面失效", backend)
                    val second = controller.tap(action.x, action.y)
                    if (first && second) {
                        GuiExecResult(true, "已在虚拟屏双击 (${action.x},${action.y})", backend)
                    } else {
                        GuiExecResult(false, "虚拟屏双击失败 (${action.x},${action.y})", backend)
                    }
                }

                is GuiPrimitive.LongPress -> {
                    val hold = action.durationMs.coerceIn(200L, 5_000L)
                    // 用同点 swipe 在 server 端线程内原子完成长按：
                    // 若走 touchDown→delay→touchUp，协程在 delay 间被取消会让 touchUp
                    // 不再执行，虚拟屏上残留一个永不抬起的触点。
                    if (controller.swipe(action.x, action.y, action.x, action.y, hold)) {
                        GuiExecResult(true, "已在虚拟屏长按 (${action.x},${action.y}) ${hold}ms", backend)
                    } else {
                        GuiExecResult(false, "虚拟屏长按失败 (${action.x},${action.y})", backend)
                    }
                }

                is GuiPrimitive.Swipe -> {
                    val dur = action.durationMs.coerceIn(50L, 5_000L)
                    if (controller.swipe(action.x1, action.y1, action.x2, action.y2, dur)) {
                        GuiExecResult(
                            true,
                            "已在虚拟屏滑动 (${action.x1},${action.y1})→(${action.x2},${action.y2})",
                            backend,
                        )
                    } else {
                        GuiExecResult(false, "虚拟屏滑动失败", backend)
                    }
                }

                is GuiPrimitive.Scroll -> {
                    val size = coordinator.getVideoSize(sessionId)
                    if (size == null) {
                        return@withContext GuiExecResult(false, "虚拟屏尺寸未知（视频流未就绪）", backend)
                    }
                    val (width, height) = size
                    val mapped = action.direction.toSwipe(
                        width = width,
                        height = height,
                        distanceRatio = action.distanceRatio,
                        durationMs = action.durationMs,
                        anchorX = action.anchorX,
                        anchorY = action.anchorY,
                    )
                    if (controller.swipe(mapped.x1, mapped.y1, mapped.x2, mapped.y2, mapped.durationMs)) {
                        GuiExecResult(
                            true,
                            "已在虚拟屏滚动 ${action.direction.name.lowercase()}",
                            backend,
                        )
                    } else {
                        GuiExecResult(false, "虚拟屏滚动失败", backend)
                    }
                }

                is GuiPrimitive.Key -> {
                    if (controller.key(action.key.keyCode)) {
                        GuiExecResult(
                            true,
                            "已在虚拟屏触发按键：${action.key.name.lowercase()} (${action.key.keyCode})",
                            backend,
                        )
                    } else {
                        GuiExecResult(false, "虚拟屏按键失败：${action.key.name.lowercase()}", backend)
                    }
                }

                is GuiPrimitive.PasteText -> pasteText(sessionId, action.text, backend, canInject)
            }
        }

    /**
     * 虚拟屏文本输入：写入系统剪贴板（虚拟屏与主屏共享同一 ClipboardService）后
     * 注入 KEYCODE_PASTE。目标应用不响应 PASTE 键时失败——此时建议 AI 改用
     * 逐字符 key 注入或该应用自带的备选输入路径。
     */
    private suspend fun pasteText(
        sessionId: String,
        text: String,
        backend: GuiBackendId,
        canInject: () -> Boolean,
    ): GuiExecResult = GuiClipboardLock.use {
        pasteTextLocked(sessionId, text, backend, replace = false, canInject = canInject)
    }

    /** AutoGLM Type 的替换语义；普通 virtual_screen_input_text 继续保留追加语义。 */
    suspend fun setText(sessionId: String, text: String, canInject: () -> Boolean = { true }): GuiExecResult = withContext(Dispatchers.IO) {
        GuiClipboardLock.use {
            pasteTextLocked(sessionId, text, GuiBackendId.SHOWER_VIRTUAL_DISPLAY, replace = true, canInject = canInject)
        }
    }

    private suspend fun pasteTextLocked(
        sessionId: String, text: String, backend: GuiBackendId, replace: Boolean, canInject: () -> Boolean = { true },
    ): GuiExecResult {
        val controller = coordinator.controller(sessionId)
        if (!canInject()) return GuiExecResult(false, "任务已暂停或画面已失效", backend)
        if (coordinator.getDisplayId(sessionId) == null) return GuiExecResult(false, "虚拟屏未创建", backend)
        if (text.isEmpty() && !replace) {
            return GuiExecResult(false, "粘贴文本为空", backend)
        }
        // 先准备剪贴板，避免写剪贴板失败时已清空用户文本。
        if (text.isNotEmpty() && !writeClipboard(text)) {
            return GuiExecResult(false, "写入剪贴板失败，虚拟屏粘贴中止", backend)
        }
        delay(PASTE_SETTLE_MS)
        if (!canInject()) return GuiExecResult(false, "文本输入已中止：任务暂停或画面失效", backend)
        if (replace) {
            if (!controller.keyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)) {
                return GuiExecResult(false, "全选按键注入失败，文本替换中止", backend)
            }
            delay(80)
        }
        if (!canInject()) return GuiExecResult(false, "文本输入已中止：任务暂停或画面失效", backend)
        val accepted = controller.key(if (text.isEmpty()) KeyEvent.KEYCODE_DEL else GuiKey.PASTE.keyCode)
        return if (accepted) {
            GuiExecResult(true, "已发送${if (replace) "替换" else "粘贴"}文本按键（${text.length} 字），请核对下一张截图", backend)
        } else {
            GuiExecResult(false, "KEYCODE_PASTE 注入失败：目标应用可能不响应粘贴键", backend)
        }
    }

    private suspend fun writeClipboard(text: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("tianyan-vscreen", text))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val PASTE_SETTLE_MS = 180L
    }
}
