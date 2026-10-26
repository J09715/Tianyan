package top.tianyan.app.harness.mcp.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthPkceTest {
    @Test
    fun `pkce challenge is stable and state comparison is exact`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertTrue(OAuthPkce.challenge(verifier).isNotBlank())
        assertTrue(OAuthPkce.challenge(verifier) == OAuthPkce.challenge(verifier))
        assertTrue(OAuthPkce.matchesState("state", "state"))
        assertFalse(OAuthPkce.matchesState("state", "state2"))
    }

    @Test
    fun `oauth endpoints require https except loopback`() {
        assertTrue(OAuthEndpointPolicy.requireSecure("https://auth.example.com/token").isNotBlank())
        assertTrue(OAuthEndpointPolicy.requireSecure("http://127.0.0.1:8765/token").isNotBlank())
        assertTrue(OAuthEndpointPolicy.requireSecure("http://localhost:8765/token").isNotBlank())
        assertTrue(runCatching { OAuthEndpointPolicy.requireSecure("http://auth.example.com/token") }.isFailure)
    }

    @Test
    fun `redirect policy rejects lookalike callback`() {
        val policy = OAuthRedirectPolicy("tianyan", "oauth", "/mcp")
        assertTrue(policy.accepts("tianyan://oauth/mcp?code=x&state=y"))
        assertFalse(policy.accepts("tianyan://evil/mcp?code=x&state=y"))
        assertFalse(policy.accepts("tianyan://oauth/other?code=x&state=y"))
    }
}
