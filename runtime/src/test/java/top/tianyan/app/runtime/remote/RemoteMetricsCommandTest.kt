package top.tianyan.app.runtime.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMetricsCommandTest {
    @Test fun `pretty name does not depend on tr and the script is valid sh`() {
        val command = metricsCommand("/work/a'b;touch injected")
        assertTrue(command.contains("df -Pk -- '/work/a'\"'\"'b;touch injected'"))
        assertTrue(command.contains("name=\$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release)"))
        assertTrue(command.contains("name=\${name#\\\"}"))
        assertTrue(command.contains("name=\${name%\\\"}"))
        assertTrue(command.contains("printf '%s\\n' \"\$name\""))
        assertFalse(Regex("""(^|[^A-Za-z0-9_])tr([^A-Za-z0-9_]|$)""").containsMatchIn(command))
        val check = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
        check.outputStream.use { it.write(command.toByteArray()) }
        val output = check.inputStream.readBytes().decodeToString()
        assertEquals(output, 0, check.waitFor())
    }
}
