package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginClipboardGrantTest {
    @Test fun nativePreviewMakesDirectionalAndInvisibleCharactersVisibleWithoutChangingInput() {
        val text = "  hello\n\u202e\u200b\"\\  "
        assertEquals("\"  hello\\u000a\\u202e\\u200b\\\"\\\\  \"", PluginCapabilityInput.preview(text))
        assertTrue(text.contains('\u202e'))
    }
    private val identity = PluginCapabilityIdentity("example.clip", "1.0.0", 1, "https://clip.plugin.tianyan.invalid", "android:local")
    private val capability = PluginCapabilityInput.CLIPBOARD_WRITE
    private fun call(id: String = "copy", text: String = "原始文本") = PluginCapabilityCall(id, capability, text)

    @Test fun approvalDisplaysAndExecutesTheExactFrozenTextOnlyOnce() = runTest {
        val copied = mutableListOf<String>()
        val session = PluginCapabilitySession(identity, mapOf(capability to { call -> copied += requireNotNull(call.text); "copied" }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        assertTrue(copied.isEmpty())
        val prompt = session.prompt.value!!
        assertEquals("原始文本", prompt.call.text)
        assertFalse(session.call(call("other", "替换文本"), identity.origin, true).success)
        assertEquals(prompt, session.prompt.value)
        session.decide(prompt.token, PluginGrantChoice.ONCE)
        assertTrue(first.await().success)
        assertEquals(listOf("原始文本"), copied)
        assertFalse(session.call(call(text = "替换文本"), identity.origin, true).success)
        val next = async { session.call(call("next", "下一段"), identity.origin, true) }
        runCurrent()
        assertEquals("下一段", session.prompt.value!!.call.text)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(next.await().success)
        assertEquals(listOf("原始文本"), copied)
    }

    @Test fun clipboardNeverAcceptsSessionPermissionEvenWhenHostOmitsOneShotFlag() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("session permission must not write") }))
        val result = async { session.call(call(), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        assertEquals("permission_denied", result.await().text)
        assertEquals(0, session.grantCount.value)
    }

    @Test fun rejectsInvalidTypedInputsBeforePrompting() = runTest {
        val info = PluginCapability("host.app-info", "application")
        val session = PluginCapabilitySession(identity, mapOf(capability to { "ok" }, info to { "info" }))
        listOf(call().copy(text = null), call(text = ""), call(text = "a".repeat(1025)), call(text = "a\u0000b"),
            PluginCapabilityCall("info", info, "unexpected")).forEach {
            assertEquals("invalid_request", session.call(it, identity.origin, true).text)
            assertNull(session.prompt.value)
        }
    }

    @Test fun revokeAndStaleDialogsCannotAuthorizeTheNextClipboardWrite() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("revoked clipboard write") }))
        val old = async { session.call(call(), identity.origin, true) }
        runCurrent()
        val token = session.prompt.value!!.token
        session.revoke()
        runCurrent()
        assertTrue(old.isCancelled)
        val next = async { session.call(call("next"), identity.origin, true) }
        runCurrent()
        assertFalse(session.decide(token, PluginGrantChoice.ONCE))
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(next.await().success)
    }
}
