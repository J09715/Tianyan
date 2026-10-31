package top.tianyan.app.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import top.tianyan.app.feature.terminal.R
import top.tianyan.app.runtime.terminal.TerminalSessionClientRouter
import java.nio.charset.StandardCharsets
import android.view.View

/**
 * Bridges Termux [TerminalView] / [TerminalSession] to Tianyan UI.
 *
 * Soft IME is owned by [TianyanTerminalHost] (TerminalView is final and cannot be subclassed);
 * rendering / scroll stay on TerminalView.
 */
class TianyanTerminalBridge(
    private val context: Context,
    private val router: TerminalSessionClientRouter?,
) : TerminalSessionClient, TerminalViewClient {

    var terminalView: TerminalView? = null
        set(value) {
            field = value
            if (value != null) {
                value.setTerminalViewClient(this)
            }
        }

    var onFontScale: ((increase: Boolean) -> Unit)? = null

    private var virtualControlActive = false
    private var virtualAltActive = false

    fun attachToRouter() { router?.attach(this) }

    fun detachFromRouter() {
        router?.attach(null)
        terminalView = null
    }

    fun toggleControlKey() {
        virtualControlActive = !virtualControlActive
    }

    fun isControlKeyActive(): Boolean = virtualControlActive

    fun toggleAltKey() {
        virtualAltActive = !virtualAltActive
    }

    fun isAltKeyActive(): Boolean = virtualAltActive

    fun currentSession(): TerminalSession? = terminalView?.currentSession

    fun sendBytes(session: TerminalSession?, bytes: ByteArray) {
        if (session != null && session.isRunning && bytes.isNotEmpty()) {
            session.write(bytes, 0, bytes.size)
        }
    }

    fun sendString(session: TerminalSession?, text: String?) {
        if (text != null) {
            sendBytes(session, text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun sendString(text: String) = sendString(currentSession(), text)

    fun sendEscape(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27))

    fun sendTab(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(9))

    fun sendSigInt(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(3))

    fun sendEof(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(4))

    fun sendArrowUp(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte()))

    fun sendArrowDown(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'B'.code.toByte()))

    fun sendArrowRight(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'C'.code.toByte()))

    fun sendArrowLeft(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'D'.code.toByte()))

    fun sendHome(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'H'.code.toByte()))

    fun sendEnd(session: TerminalSession? = currentSession()) =
        sendBytes(session, byteArrayOf(27, '['.code.toByte(), 'F'.code.toByte()))

    // region TerminalSessionClient — mirror TerminalBridge.java
    override fun onTextChanged(changedSession: TerminalSession) {
        val view = terminalView ?: return
        // Our terminal-view AAR exposes only onScreenUpdated() (no skipScrolling overload).
        // Termux still jumps to bottom (mTopRow=0) on every update; restore the user's
        // scrollback offset so live output does not yank the viewport while reading history.
        val savedTop = view.topRow
        val readingHistory = savedTop < -1
        view.onScreenUpdated()
        if (readingHistory && view.topRow != savedTop) {
            view.topRow = savedTop
            view.invalidate()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.w("TianyanTerminal", "session finished pid=${finishedSession.pid} exit=${finishedSession.exitStatus}")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clipboard_label), text))
        Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        if (!session.isRunning) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            sendString(session, text)
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "TerminalSession", e)
    }
    // endregion

    // region TerminalViewClient — mirror TerminalBridge.java
    override fun onScale(scale: Float): Float {
        if (scale < 0.92f || scale > 1.08f) {
            onFontScale?.invoke(scale > 1.0f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView ?: return
        // IME is owned by TianyanTerminalHost (parent); TerminalView itself is not focusable.
        val imeTarget = (view.parent as? View) ?: view
        imeTarget.requestFocus()
        val imm = imeTarget.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(imeTarget, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** Soft keyboards under Compose need char-based inputType for reliable key-repeat DEL. */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {
        if (!copyMode) (terminalView?.parent as? TianyanTerminalHost)?.restoreInputFocus()
    }
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean {
        val host = terminalView?.parent as? TianyanTerminalHost ?: return false
        host.startTextSelection(event)
        return true
    }

    override fun readControlKey(): Boolean {
        val active = virtualControlActive
        virtualControlActive = false
        return active
    }

    override fun readAltKey(): Boolean {
        val active = virtualAltActive
        virtualAltActive = false
        return active
    }

    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit
    // endregion
}
