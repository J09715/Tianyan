package top.tianyan.app.runtime.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes Termux [TerminalSessionClient] callbacks to the active UI bridge
 * (Compose [TerminalView] host). Sessions are created in [TerminalSessionManager]
 * before a view exists, so the delegate is attached later by the screen.
 */
class TerminalSessionClientRouter() : TerminalSessionClient {
    private val delegate = AtomicReference<TerminalSessionClient?>(null)

    fun attach(client: TerminalSessionClient?) {
        delegate.set(client)
    }

    private inline fun withDelegate(block: TerminalSessionClient.() -> Unit) {
        delegate.get()?.block()
    }

    override fun onTextChanged(changedSession: TerminalSession) =
        withDelegate { onTextChanged(changedSession) }

    override fun onTitleChanged(changedSession: TerminalSession) =
        withDelegate { onTitleChanged(changedSession) }

    override fun onSessionFinished(finishedSession: TerminalSession) =
        withDelegate { onSessionFinished(finishedSession) }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) =
        withDelegate { onCopyTextToClipboard(session, text) }

    override fun onPasteTextFromClipboard(session: TerminalSession) =
        withDelegate { onPasteTextFromClipboard(session) }

    override fun onBell(session: TerminalSession) =
        withDelegate { onBell(session) }

    override fun onColorsChanged(session: TerminalSession) =
        withDelegate { onColorsChanged(session) }

    override fun onTerminalCursorStateChange(state: Boolean) =
        withDelegate { onTerminalCursorStateChange(state) }

    override fun getTerminalCursorStyle(): Int? =
        delegate.get()?.terminalCursorStyle

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
        withDelegate { logError(tag, message) }
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
        withDelegate { logWarn(tag, message) }
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
        withDelegate { logInfo(tag, message) }
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
        withDelegate { logDebug(tag, message) }
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
        withDelegate { logVerbose(tag, message) }
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
        withDelegate { logStackTraceWithMessage(tag, message, e) }
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "TerminalSession", e)
        withDelegate { logStackTrace(tag, e) }
    }
}
