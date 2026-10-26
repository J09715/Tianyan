package top.tianyan.app.runtime.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshEnvironmentTest {
    @Test fun `command environment replaces settings values and bridge credentials never leave`() {
        val merged = forwardedSshEnvironment(
            user = mapOf(
                "FROM_SETTINGS" to "user",
                "KEEP" to "kept",
                "TIANYAN_BRIDGE_TOKEN" to "bridge-secret",
                "TIANYAN_BRIDGE_ACTIONS" to "host.shell",
                "tianyan_bridge_url" to "http://127.0.0.1:7980",
                "bad-key" to "skip",
                SSH_ENV_PROBE_KEY to "spoof",
            ),
            command = mapOf(
                "FROM_SETTINGS" to "command",
                "EXTRA" to "a'b",
                "TIANYAN_BRIDGE_TOKEN" to "command-secret",
                SSH_ENV_PROBE_KEY to "spoof-command",
            ),
        )
        assertEquals(mapOf("FROM_SETTINGS" to "command", "KEEP" to "kept", "EXTRA" to "a'b"), merged)
        assertTrue(merged.keys.none { isBridgeCredentialKey(it) })
        assertFalse(merged.containsKey(SSH_ENV_PROBE_KEY))
    }

    @Test fun `invalid command keys are rejected and nul values are rejected`() {
        try {
            forwardedSshEnvironment(emptyMap(), mapOf("X;touch hacked" to "value"))
            error("invalid key accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            forwardedSshEnvironment(mapOf("SAFE" to "a\u0000b"), emptyMap())
            error("nul accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `quoted exports keep metacharacters literal and do not source a profile`() {
        val command = remoteCommand(
            "printf ok",
            "/tmp/a'b",
            forwardedSshEnvironment(mapOf("SECRET_VALUE" to "a'b\n\$(bad)`tick`\\path"), emptyMap()),
        )
        assertTrue(command.contains("export SECRET_VALUE='a'\"'\"'b\n\$(bad)`tick`\\path';"))
        assertTrue(command.endsWith("printf ok\n}"))
        assertFalse(command.contains("/etc/profile"))
        assertFalse(command.contains("source "))
        assertFalse(command.contains("profile.d"))
    }
}
