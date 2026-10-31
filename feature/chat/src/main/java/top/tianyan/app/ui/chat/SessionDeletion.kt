package top.tianyan.app.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tianyan.app.harness.diagnostics.RequestDiagnosticsPersistenceException
import top.tianyan.app.harness.session.SessionControl

/** Archive cleanup failures are recoverable UI feedback; coroutine cancellation propagates. */
internal class SessionDeletionFeedback {
    private val deletionError = MutableStateFlow<String?>(null)

    fun errors(runError: StateFlow<String?>, scope: CoroutineScope): StateFlow<String?> =
        combine(deletionError, runError) { deletion, run -> deletion ?: run }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() { deletionError.value = null }

    fun delete(scope: CoroutineScope, control: SessionControl, sessionId: String, failureMessage: String) = scope.launch {
        try {
            A2uiChatBridge.releaseSession(sessionId)
            control.deleteSession(sessionId)
            deletionError.value = null
        } catch (_: RequestDiagnosticsPersistenceException) {
            deletionError.value = failureMessage
        }
    }
}
