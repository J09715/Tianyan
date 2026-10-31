package top.tianyan.app.ui.terminal

import android.content.Context
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.termux.view.TerminalView

/**
 * Host around final Termux [TerminalView].
 *
 * Soft IME attaches here (not on the final TerminalView), so we can report fake
 * preceding text and keep backspace auto-repeat working. Touch/scroll still go to
 * the inner TerminalView; key/IME bytes go to its attached session.
 */
class TianyanTerminalHost(context: Context) : FrameLayout(context) {

    val terminalView: TerminalView = TerminalView(context, null).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        // Keep IME on the host except while native text selection needs focus.
        isClickable = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isMotionEventSplittingEnabled = false
        addView(
            terminalView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onCheckIsTextEditor(): Boolean = true

    fun startTextSelection(event: MotionEvent) {
        if (terminalView.mEmulator == null || terminalView.isSelectingText) return
        // Termux refuses to show selection handles unless requestFocus() succeeds.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        terminalView.startTextSelectionMode(event)
        if (!terminalView.isSelectingText) restoreInputFocus()
    }

    fun restoreInputFocus() {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        terminalView.isFocusableInTouchMode = false
        terminalView.isFocusable = false
        requestFocus()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Char-based type so OEM IMEs keep sending repeated deleteSurroundingText.
        outAttrs.inputType =
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                // Non-empty so Gboard/OEM keyboards continue auto-repeat delete.
                return "\u200B".repeat(n.coerceIn(1, 64))
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    writeUtf8(text.toString())
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                val editable = editable
                if (editable != null && editable.isNotEmpty()) {
                    writeUtf8(editable.toString())
                    editable.clear()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                emitBackspaces(beforeLength.coerceAtLeast(1))
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                emitBackspaces(beforeLength.coerceAtLeast(1))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        if (event.action == KeyEvent.ACTION_DOWN) emitBackspaces(1)
                        true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) writeUtf8("\r")
                        true
                    }
                    else -> {
                        // Let TerminalView handle arrows / modifiers via key dispatch.
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            terminalView.onKeyDown(event.keyCode, event)
                        } else {
                            terminalView.onKeyUp(event.keyCode, event)
                        }
                        true
                    }
                }
            }

            private fun emitBackspaces(count: Int) {
                // DEL (0x7F) matches Termux KeyHandler for KEYCODE_DEL.
                val del = ByteArray(count) { 0x7F.toByte() }
                writeBytes(del)
            }

            private fun writeUtf8(text: String) {
                if (text.isEmpty()) return
                // Soft Enter often arrives as \n; PTY shells expect \r.
                val normalized = text.replace('\n', '\r')
                writeBytes(normalized.toByteArray(Charsets.UTF_8))
            }

            private fun writeBytes(bytes: ByteArray) {
                val session = terminalView.currentSession ?: return
                if (!session.isRunning || bytes.isEmpty()) return
                session.write(bytes, 0, bytes.size)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Keep pan/fling/selection on TerminalView; take focus here for IME.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !terminalView.isSelectingText) {
            requestFocus()
        }
        return terminalView.dispatchTouchEvent(ev)
    }
}
