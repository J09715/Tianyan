package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.BotAssignmentStatus

class OfficeSemanticsTest {
    @Test
    fun `reasoning preview keeps the latest meaningful sentence`() {
        assertEquals(
            "正在核对 PRoot 挂载路径",
            reasoningSnippet("先看日志。\n**正在核对 PRoot 挂载路径**"),
        )
        assertEquals(
            "The migration path is stable.",
            reasoningSnippet("I checked the logs. The migration path is stable."),
        )
        assertEquals(null, reasoningSnippet(""))
    }

    @Test
    fun `output preview keeps the newest line and tail of long text`() {
        assertEquals("最后一行正在生成", outputSnippet("第一行\n最后一行正在生成"))
        val long = outputSnippet("12345678901234567890", max = 6)
        assertTrue(long != null && long.startsWith("…") && long.endsWith("567890"))
        assertEquals(null, outputSnippet("  \n "))
    }

    @Test
    fun `role keywords map to the right idle voice`() {
        assertEquals(OfficeRoleKind.FRONTEND, officeRoleKind("前端工程师"))
        assertEquals(OfficeRoleKind.TEST, officeRoleKind("QA Engineer"))
        assertEquals(OfficeRoleKind.OPS, officeRoleKind("SRE / 部署"))
        assertEquals(OfficeRoleKind.PRODUCT, officeRoleKind("产品经理"))
        assertEquals(OfficeRoleKind.DATA, officeRoleKind("数据科学家"))
        assertEquals(OfficeRoleKind.DESIGN, officeRoleKind("视觉设计师"))
        assertEquals(OfficeRoleKind.BACKEND, officeRoleKind("后端工程师"))
        assertEquals(OfficeRoleKind.WRITER, officeRoleKind("技术写作者"))
        assertEquals(OfficeRoleKind.GENERAL, officeRoleKind("工程师"))
    }

    @Test
    fun `assignment statuses land in the three board columns`() {
        assertEquals(OfficeBoardColumn.TODO, officeBoardColumn(BotAssignmentStatus.QUEUED))
        assertEquals(OfficeBoardColumn.DOING, officeBoardColumn(BotAssignmentStatus.RUNNING))
        assertEquals(OfficeBoardColumn.DOING, officeBoardColumn(BotAssignmentStatus.BLOCKED))
        assertEquals(OfficeBoardColumn.DOING, officeBoardColumn(BotAssignmentStatus.INTERRUPTED))
        assertEquals(OfficeBoardColumn.DONE, officeBoardColumn(BotAssignmentStatus.COMPLETED))
        assertEquals(OfficeBoardColumn.DONE, officeBoardColumn(BotAssignmentStatus.FAILED))
        assertEquals(OfficeBoardColumn.DONE, officeBoardColumn(BotAssignmentStatus.CANCELLED))
    }
}
