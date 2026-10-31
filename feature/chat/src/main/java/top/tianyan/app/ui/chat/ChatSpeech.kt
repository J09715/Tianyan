package top.tianyan.app.ui.chat

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.HarnessMessage
import java.util.Locale

internal data class ChatSpeechToggle(val enabled: Boolean, val onToggle: () -> Unit)

/** Speech belongs to the visible chat; leaving it releases the engine and queued audio. */
@Composable
internal fun rememberChatSpeech(
    sessionId: String,
    messages: List<HarnessMessage>,
    running: Boolean,
    initializing: Boolean,
): ChatSpeechToggle? {
    if (LocalInspectionMode.current) return null
    val preferences = koinInject<AgentPreferences>()
    val enabled by preferences.autoReadReplies.collectAsStateWithLifecycle(false)
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var ready by remember { mutableStateOf(false) }
    val gate = remember { ChatSpeechGate() }
    val fail = rememberUpdatedState {
        Toast.makeText(context, R.string.chat_speech_unavailable, Toast.LENGTH_LONG).show()
        scope.launch { preferences.setAutoReadReplies(false) }
    }
    val engine = remember(context) { SystemChatSpeech(context) { fail.value() } }
    DisposableEffect(owner, engine) {
        val observer = LifecycleEventObserver { _, _ ->
            active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!active) engine.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            engine.close()
        }
    }
    DisposableEffect(enabled, engine) {
        ready = false
        if (enabled) engine.start { ready = true } else engine.close()
        onDispose { engine.close() }
    }
    LaunchedEffect(sessionId, enabled, active, initializing, running, messages, ready) {
        if (gate.sessionId != sessionId || !enabled || !active || initializing) engine.stop()
        gate.update(sessionId, messages, running, enabled && active && !initializing)
        // Keep newly completed replies until asynchronous engine initialization finishes.
        if (ready) gate.drain().forEach(engine::speak)
    }
    return ChatSpeechToggle(enabled) {
        if (enabled) engine.stop()
        scope.launch { preferences.setAutoReadReplies(!enabled) }
    }
}

private class SystemChatSpeech(
    private val context: android.content.Context,
    private val onFailure: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var failed = false

    fun start(onReady: () -> Unit) {
        close()
        val token = generation
        failed = false
        try {
            tts = TextToSpeech(context) { status ->
                handler.post {
                    if (token != generation) return@post
                    val engine = tts ?: return@post
                    if (status != TextToSpeech.SUCCESS) {
                        fail()
                    } else {
                        val language = engine.setLanguage(Locale.getDefault())
                        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                            fail()
                        } else {
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit
                                override fun onDone(utteranceId: String?) = Unit
                                @Deprecated("Required by platform")
                                override fun onError(utteranceId: String?) {
                                    handler.post { if (token == generation) fail() }
                                }
                            })
                            onReady()
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            fail()
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        text.chunked(TextToSpeech.getMaxSpeechInputLength()).forEachIndexed { index, chunk ->
            if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "chat-$generation-${System.nanoTime()}-$index") == TextToSpeech.ERROR) {
                fail()
                return
            }
        }
    }

    fun stop() { tts?.stop() }

    fun close() {
        generation++
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun fail() {
        if (failed) return
        failed = true
        close()
        onFailure()
    }
}
