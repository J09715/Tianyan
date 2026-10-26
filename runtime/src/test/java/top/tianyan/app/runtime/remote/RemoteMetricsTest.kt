package top.tianyan.app.runtime.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteMetricsTest {
    @Test fun `metrics are parsed from the SSH response without phone defaults`() {
        val metrics = parseMetrics("""
            distribution${'\t'}Remote Linux
            architecture${'\t'}x86_64
            kernel${'\t'}6.8.0
            memoryTotal${'\t'}8192
            memoryUsed${'\t'}1024
            diskTotal${'\t'}107374182400
            diskUsed${'\t'}10737418240
            load${'\t'}0.1 0.2 0.3
            uptime${'\t'}3600
            processes${'\t'}42
        """.trimIndent())
        assertEquals("x86_64", metrics.architecture)
        assertEquals("Remote Linux", metrics.distribution)
        assertEquals(8192L, metrics.memoryTotalMb)
        assertEquals(100.0, metrics.diskTotalGb, 0.01)
        assertEquals(42L, metrics.processCount)
    }
    @Test fun `incomplete metrics cannot masquerade as a connected phone dashboard`() {
        try { parseMetrics("architecture\tx86_64"); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun `working directory is shell quoted when collecting disk metrics`() {
        val command = metricsCommand("/work/a'b;touch injected")
        assertTrue(command.contains("df -Pk -- '/work/a'\"'\"'b;touch injected'"))
    }
}
