package top.tianyan.app.harness.groupchat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupToolStepDetailTest {
    @Test
    fun `file tools expose the last path segment`() {
        assertEquals(
            "PixelOfficeSprite.kt",
            toolStepDetail("write_file", buildJsonObject { put("path", "/workspace/app/ui/PixelOfficeSprite.kt") }),
        )
        assertEquals(
            "Main.kt",
            toolStepDetail("apply_patch", buildJsonObject { put("file_path", "C:\\work\\Main.kt") }),
        )
    }

    @Test
    fun `shell commands use the first line and searches use the query`() {
        assertEquals(
            "./gradlew testDebugUnitTest",
            toolStepDetail("shell", buildJsonObject { put("command", "./gradlew testDebugUnitTest\n--rerun") }),
        )
        assertEquals(
            "approval gate",
            toolStepDetail("grep", buildJsonObject { put("pattern", "approval gate") }),
        )
        assertEquals(
            "github.com/tianyan",
            toolStepDetail("fetch", buildJsonObject { put("url", "https://github.com/tianyan") }),
        )
    }

    @Test
    fun `capability arguments are inspected and empty calls stay blank`() {
        assertEquals(
            "阿测",
            toolStepDetail(
                "use_capability",
                buildJsonObject {
                    put("tool", "assign-task")
                    put("arguments", buildJsonObject { put("bot_name", "阿测") })
                },
            ),
        )
        assertNull(toolStepDetail("read_file", buildJsonObject { }))
    }
}
