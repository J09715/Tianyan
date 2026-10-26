package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginCapabilitySessionTest {
    private val identity = PluginCapabilityIdentity("example.web", "1.0.0", 1, "https://example.plugin.tianyan.invalid", "android:local")
    private val capability = PluginCapability("host.app-info", "application")
    private fun call(id: String = "request-1") = PluginCapabilityCall(id, capability)

    @Test fun readOnlyCapabilitiesCacheLongerThoughEveryGrantStillExpires() = runTest {
        var now = 0L
        val service = PluginCapability("host.service-request", "service")
        val session = PluginCapabilitySession(identity, mapOf(capability to { "ok" }, service to { "ok" }),
            nowMillis = { now }, grantLifetimeMillis = 100, longLivedGrantMillis = 1_000,
            longLivedCapabilities = setOf(capability, PluginCapabilityInput.CLIPBOARD_WRITE))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        assertTrue(first.await().success)
        now = 999
        assertTrue(session.call(call("read-only-later"), identity.origin, true).success)
        now = 1_000
        val expired = async { session.call(call("read-only-expired"), identity.origin, true) }
        runCurrent()
        assertNotNull(session.prompt.value)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(expired.await().success)
        val svc = async { session.call(PluginCapabilityCall("svc", service, "index.html"), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        assertTrue(svc.await().success)
        now = 1_099
        assertTrue(session.call(PluginCapabilityCall("svc-later", service, "index.html"), identity.origin, true).success)
    }

    @Test fun executorWaitsForNativeConfirmationAndOnceDoesNotAuthorizeNextCall() = runTest {
        var executions = 0
        val session = PluginCapabilitySession(identity, mapOf(capability to { executions++; "ok" }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        assertEquals(0, executions)
        val prompt = requireNotNull(session.prompt.value)
        assertEquals(identity, prompt.identity)
        assertTrue(session.decide(prompt.token, PluginGrantChoice.ONCE))
        assertTrue(first.await().success)
        assertEquals(1, executions)
        val next = async { session.call(call("request-2"), identity.origin, true) }
        runCurrent()
        assertNotNull(session.prompt.value)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(next.await().success)
        assertEquals(1, executions)
    }

    @Test fun rejectsWrongOriginSubframesUnknownActionsAndTargetChangesBeforePrompting() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("must not execute") }))
        listOf("null", "http://example.plugin.tianyan.invalid", "${identity.origin}.evil", "${identity.origin}:443").forEach {
            assertEquals("origin_denied", session.call(call(), it, true).text)
        }
        assertEquals("origin_denied", session.call(call(), identity.origin, false).text)
        for (other in listOf(PluginCapability("shell", "application"), capability.copy(target = "other"))) {
            assertEquals("capability_denied", session.call(PluginCapabilityCall("x", other), identity.origin, true).text)
        }
        assertNull(session.prompt.value)
    }

    @Test fun denialAndStaleTokensNeverExecuteOrApproveAnotherRequest() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("must not execute") }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        val old = session.prompt.value!!.token
        assertFalse(session.decide("forged", PluginGrantChoice.ONCE))
        assertTrue(session.decide(old, PluginGrantChoice.DENY))
        assertFalse(first.await().success)
        val next = async { session.call(call("second"), identity.origin, true) }
        runCurrent()
        assertFalse(session.decide(old, PluginGrantChoice.SESSION))
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(next.await().success)
    }

    @Test fun grantExpiresAndDoesNotTransferAcrossVersionEnvironmentOrGeneration() = runTest {
        var now = 0L
        val session = PluginCapabilitySession(identity, mapOf(capability to { "ok" }), nowMillis = { now }, grantLifetimeMillis = 100)
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        assertTrue(first.await().success)
        now = 99
        assertTrue(session.call(call("second"), identity.origin, true).success)
        now = 100
        val expired = async { session.call(call("third"), identity.origin, true) }
        runCurrent()
        assertNotNull(session.prompt.value)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        expired.await()
        for (other in listOf(identity.copy(version = "2.0.0"), identity.copy(generation = 2), identity.copy(environment = "ssh:remote"))) {
            val isolated = PluginCapabilitySession(other, mapOf(capability to { "ok" }))
            val result = async { isolated.call(call(), other.origin, true) }
            runCurrent()
            assertNotNull(isolated.prompt.value)
            isolated.decide(isolated.prompt.value!!.token, PluginGrantChoice.DENY)
            assertFalse(result.await().success)
        }
    }

    @Test fun preventsReplayAndLimitsOutstandingRequests() = runTest {
        var executions = 0
        val session = PluginCapabilitySession(identity, mapOf(capability to { executions++; "ok" }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        assertEquals("busy", session.call(call("second"), identity.origin, true).text)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        first.await()
        assertEquals("replayed_request", session.call(call(), identity.origin, true).text)
        for (n in 2..256) assertTrue(session.call(call("request-$n"), identity.origin, true).success)
        assertEquals("session_limit", session.call(call("overflow"), identity.origin, true).text)
        assertEquals(256, executions)
    }

    @Test fun revocationCancelsInFlightExecutorAndRequiresFreshPermission() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val session = PluginCapabilitySession(identity, mapOf(capability to {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.SESSION)
        started.await()
        session.revoke()
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(cancelled)
        assertEquals(0, session.grantCount.value)
        val next = async { session.call(call("second"), identity.origin, true) }
        runCurrent()
        assertNotNull(session.prompt.value)
        session.decide(session.prompt.value!!.token, PluginGrantChoice.DENY)
        assertFalse(next.await().success)
    }

    @Test fun closeInvalidatesPendingConfirmationAndPermanentlyClosesDocument() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("must not execute") }))
        val first = async { session.call(call(), identity.origin, true) }
        runCurrent()
        val old = session.prompt.value!!.token
        session.close()
        session.close()
        runCurrent()
        assertTrue(first.isCancelled)
        assertFalse(session.decide(old, PluginGrantChoice.SESSION))
        assertNull(session.prompt.value)
        assertFalse(session.call(call("second"), identity.origin, true).success)
    }

    @Test fun approvalTimeoutClearsNativePromptAndLateApprovalFails() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("must not execute") }), approvalTimeoutMillis = 100)
        val result = async { session.call(call(), identity.origin, true) }
        runCurrent()
        val token = session.prompt.value!!.token
        advanceTimeBy(101)
        assertEquals("permission_denied", result.await().text)
        assertNull(session.prompt.value)
        assertFalse(session.decide(token, PluginGrantChoice.ONCE))
    }

    @Test fun exceptionsAreRedactedAndOversizedResultsAreRejected() = runTest {
        for ((executor, expected) in listOf<suspend () -> String>({ error("secret-token") }, { "x".repeat(32_769) }).zip(listOf("execution_failed", "result_too_large"))) {
            val session = PluginCapabilitySession(identity, mapOf(capability to { _: PluginCapabilityCall -> executor() }))
            val result = async { session.call(call(), identity.origin, true) }
            runCurrent()
            session.decide(session.prompt.value!!.token, PluginGrantChoice.ONCE)
            assertEquals(expected, result.await().text)
        }
    }

    @Test fun executorTimeoutDoesNotReportSuccess() = runTest {
        val session = PluginCapabilitySession(identity, mapOf(capability to { awaitCancellation() }), executionTimeoutMillis = 100)
        val result = async { session.call(call(), identity.origin, true) }
        runCurrent()
        session.decide(session.prompt.value!!.token, PluginGrantChoice.ONCE)
        runCurrent()
        advanceTimeBy(101)
        assertEquals("execution_timeout", result.await().text)
    }

    @Test fun stoppedGenerationCannotUseGrantsOrConfirmAnOldDialog() = runTest {
        var available = true
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("must not execute") }), isAvailable = { available })
        val result = async { session.call(call(), identity.origin, true) }
        runCurrent()
        val token = session.prompt.value!!.token
        available = false
        assertFalse(session.decide(token, PluginGrantChoice.ONCE))
        assertFalse(session.call(call("next"), identity.origin, true).success)
        session.close()
        runCurrent()
        assertTrue(result.isCancelled)
    }
}
