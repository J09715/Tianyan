package top.tianyan.app.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLayoutTest {
    @Test
    fun keepsProgramDataAndCommandEntrypointsSeparated() {
        assertEquals("/opt/tianyan/tools/openclaw", ToolLayout.toolDirectory("openclaw"))
        assertEquals("/opt/tianyan/data/openclaw", ToolLayout.toolDataDirectory("openclaw"))
        assertEquals("/opt/tianyan/bin/openclaw", ToolLayout.commandPath("openclaw"))
    }
}
