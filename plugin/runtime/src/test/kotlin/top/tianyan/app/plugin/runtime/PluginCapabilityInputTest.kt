package top.tianyan.app.plugin.runtime

import org.junit.Assert.*
import org.junit.Test

class PluginCapabilityInputTest {
    private fun call(action: String, target: String, text: String? = null) =
        PluginCapabilityCall("request-1", PluginCapability(action, target), text)

    @Test fun servicePathsStayRelativeAndTraversalFree() {
        assertTrue(PluginCapabilityInput.validServicePath("index.html"))
        assertTrue(PluginCapabilityInput.validServicePath("api/v1/status.json"))
        assertTrue(PluginCapabilityInput.validServicePath("a_b-c.d"))
        for (path in listOf("", "/absolute", ".", "./b", "../up", "a/../b", "a b", "a:b", "a?b", "a#b", "a\u0000b", "a".repeat(257))) {
            assertFalse(path, PluginCapabilityInput.validServicePath(path))
        }
    }

    @Test fun onlyTheServiceActionsAcceptAPathPayload() {
        assertTrue(PluginCapabilityInput.valid(call("host.service-request", "service", "api/status")))
        assertTrue(PluginCapabilityInput.valid(call("host.service-stream", "service", "events")))
        assertFalse(PluginCapabilityInput.valid(call("host.service-request", "service")))
        assertFalse(PluginCapabilityInput.valid(call("host.service-request", "service", "../up")))
        assertFalse(PluginCapabilityInput.valid(call("host.service-request", "service", "/absolute")))
        assertTrue(PluginCapabilityInput.valid(call("host.app-info", "application")))
        assertFalse(PluginCapabilityInput.valid(call("host.app-info", "application", "payload")))
        assertTrue(PluginCapabilityInput.valid(call("host.clipboard-write", "clipboard", "text")))
        assertFalse(PluginCapabilityInput.valid(call("host.clipboard-write", "clipboard")))
    }

    @Test fun onlyAnAbsoluteApkPathIsAcceptedForInstallation() {
        assertTrue(PluginCapabilityInput.validApkPath("/sdcard/Download/app.apk"))
        assertTrue(PluginCapabilityInput.validApkPath("/storage/emulated/0/Download/App.APK"))
        assertTrue(PluginCapabilityInput.validApkPath("/workspace/build/output.apk"))
        for (path in listOf(
            "app.apk", "/app.apk", "/sdcard/Download/app.zip", "/sdcard/../app.apk",
            "/sdcard/./app.apk", "/sdcard//app.apk", "/sdcard/a b/app.apk", "/sdcard/" + "a".repeat(260) + ".apk",
        )) {
            assertFalse(path, PluginCapabilityInput.validApkPath(path))
        }
        assertTrue(PluginCapabilityInput.valid(call("host.install-apk", "apk", "/sdcard/Download/app.apk")))
        assertFalse(PluginCapabilityInput.valid(call("host.install-apk", "apk")))
        assertFalse(PluginCapabilityInput.valid(call("host.install-apk", "apk", "app.apk")))
        assertTrue(PluginCapabilityInput.valid(call("host.logcat", "logcat")))
        assertFalse(PluginCapabilityInput.valid(call("host.logcat", "logcat", "200 lines")))
    }

    @Test fun writeActionsAreNeverSessionEligible() {
        assertEquals(setOf(PluginCapabilityInput.CLIPBOARD_WRITE, PluginCapabilityInput.INSTALL_APK), PluginCapabilityInput.ONE_SHOT)
        assertTrue(PluginCapabilityInput.ONE_SHOT.none { it in setOf(PluginCapabilityInput.LOGCAT, PluginCapabilityInput.SERVICE_REQUEST) })
    }

    @Test fun theServiceActionSetMatchesTheRegisteredCapabilities() {
        assertEquals(
            setOf(PluginCapabilityInput.SERVICE_REQUEST, PluginCapabilityInput.SERVICE_STREAM),
            PluginCapabilityInput.SERVICE_ACTIONS.map { PluginCapability(it, "service") }.toSet(),
        )
    }
}
