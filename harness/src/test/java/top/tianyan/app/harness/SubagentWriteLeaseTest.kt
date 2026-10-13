package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 写租约闸门守卫。
 *
 * writePaths 过去只渲染进子任务提示词，而 WRITE/EDIT 在工作区内本就免审批，
 * 于是「只读子代理」实际能写工作区任意文件。这里固定代码级闸门的行为：
 * 空租约拒绝一切写入，`["*"]` 全放行，精确路径只放行其自身与子路径。
 */
class SubagentWriteLeaseTest {

    private val json = Json

    @Test
    fun readOnlyTaskRejectsAnyWrite() {
        assertNotNull(writeLeaseViolation(emptyList(), "feature/chat/ChatViewModel.kt"))
        assertNotNull(writeLeaseViolation(emptyList(), "/workspace/a/b.txt"))
    }

    @Test
    fun wholeWorkspaceLeaseAllowsEveryPath() {
        assertNull(writeLeaseViolation(listOf("*"), "anything/at/all.txt"))
        assertNull(writeLeaseViolation(listOf("."), "anything/at/all.txt"))
    }

    @Test
    fun scopedLeaseAllowsOwnPathAndDescendants() {
        val lease = listOf("feature/chat/src/main")
        assertNull(writeLeaseViolation(lease, "feature/chat/src/main/java/A.kt"))
        assertNull(writeLeaseViolation(lease, "/feature/chat/src/main/java/A.kt"))
        assertNotNull(writeLeaseViolation(lease, "feature/settings/src/main/B.kt"))
    }

    @Test
    fun blankTargetPathIsRejectedRatherThanPassing() {
        assertNotNull(writeLeaseViolation(listOf("*"), ""))
    }

    @Test
    fun toolGateOnlyCoversFileMutatingTools() {
        val writeArgs = json.parseToJsonElement("""{"path":"a.txt","content":"x"}""") as JsonObject
        val downloadArgs = json.parseToJsonElement("""{"url":"https://x/y","destination":"dist/y"}""") as JsonObject
        val readArgs = json.parseToJsonElement("""{"path":"a.txt"}""") as JsonObject

        assertNotNull(writeLeaseViolationForTool(HarnessTool.WRITE, writeArgs, emptyList()))
        assertNotNull(writeLeaseViolationForTool(HarnessTool.EDIT, writeArgs, emptyList()))
        assertNotNull(writeLeaseViolationForTool(HarnessTool.DOWNLOAD, downloadArgs, emptyList()))
        assertNull(writeLeaseViolationForTool(HarnessTool.READ, readArgs, emptyList()))
        assertNull(writeLeaseViolationForTool(HarnessTool.WRITE, writeArgs, listOf("*")))
    }

    @Test
    fun nonStringPathDoesNotBecomeALiteralJsonPath() {
        val weird = json.parseToJsonElement("""{"path":{"nested":true}}""") as JsonObject
        assertNotNull(writeLeaseViolationForTool(HarnessTool.WRITE, weird, emptyList()))
        assertNotNull(writeLeaseViolationForTool(HarnessTool.WRITE, weird, listOf("*")))
    }
}