package top.tianyan.app.ui.chat

import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage

/** Baseline existing transcripts; only completed new replies enter the speech queue. */
internal class ChatSpeechGate {
    var sessionId: String? = null
        private set
    private var enabled = false
    private var since = 0L
    private val seen = mutableSetOf<String>()
    private val pending = mutableListOf<String>()

    fun update(session: String, messages: List<HarnessMessage>, running: Boolean, active: Boolean) {
        if (sessionId != session || enabled != active) {
            sessionId = session
            enabled = active
            since = System.currentTimeMillis()
            seen.clear()
            seen.addAll(messages.map { it.id })
            pending.clear()
            return
        }
        if (!active || running) return
        messages.filterIsInstance<AssistantText>().forEach { message ->
            if (seen.add(message.id) && message.createdAt >= since) {
                speechText(message.text).takeIf(String::isNotBlank)?.let(pending::add)
            }
        }
    }

    fun drain(): List<String> = pending.toList().also { pending.clear() }
}

/** Keep prose, avoid reading code blocks, image URLs and Markdown punctuation aloud. */
internal fun speechText(text: String): String = text
    .replace(Regex("(?s)<think>.*?(?:</think>|$)"), " ")
    .replace(Regex("(?s)```.*?```|~~~.*?~~~"), " ")
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s*|[-*+]\\s+)"), "")
    .replace(Regex("[`*_~]"), "")
    .trim()
