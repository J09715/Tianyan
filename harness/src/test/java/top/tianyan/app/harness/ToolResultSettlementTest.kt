package top.tianyan.app.harness

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果落库的行为锁定。
 *
 * 这条路径出过一类很难自查的故障：工具气泡在、结果气泡不在，一条 stdout/exit code/stderr
 * 全丢，而且同回合后面的工具跟着一起消失——因为 `toolSettled` 抛出会一路掀翻整个回合。
 * 这里钉住「输出不能丢」：落库保证可以退，输出必须留着。
 */
class ToolResultSettlementTest {

    private fun result(id: String) = ToolResult(
        id = id,
        createdAt = 1L,
        toolCallId = "call-$id",
        success = true,
        output = "exit 0 · 12 ms\nhello",
    )

    @Test
    fun `result survives when the operation row is already gone`() = runBlocking {
        val appended = mutableListOf<ToolResult>()
        val settleCalls = AtomicInteger()

        settleToolResult(
            operationId = "op-missing",
            round = 3,
            toolName = "base",
            outcome = result("r1"),
            // 模拟 operation 行在 intent 之后、settle 之前被清掉：settle() 第一步
            // requireOperation() 直接抛。
            settle = { id, _, _, _ -> error("Missing harness operation $id") },
            appendToTree = { appended += it },
            log = { _, _ -> },
        )

        assertEquals("结算失败时结果必须落到消息树，输出不能随异常一起消失", 1, appended.size)
        assertEquals("r1", appended.first().id)
        assertEquals(0, settleCalls.get())
    }

    @Test
    fun `result is written straight to the tree when there is no operation`() = runBlocking {
        val appended = mutableListOf<ToolResult>()

        settleToolResult(
            operationId = null,
            round = 0,
            toolName = "base",
            outcome = result("r2"),
            settle = { _, _, _, _ -> error("没有 operation 时不该尝试结算") },
            appendToTree = { appended += it },
            log = { _, _ -> },
        )

        assertEquals(1, appended.size)
        assertEquals("r2", appended.first().id)
    }

    @Test
    fun `a successful settle does not double-write to the tree`() = runBlocking {
        val appended = mutableListOf<ToolResult>()
        val settleCalls = AtomicInteger()

        settleToolResult(
            operationId = "op-live",
            round = 1,
            toolName = "base",
            outcome = result("r3"),
            settle = { _, _, _, _ -> settleCalls.incrementAndGet() },
            appendToTree = { appended += it },
            log = { _, _ -> },
        )

        assertEquals(1, settleCalls.get())
        assertTrue("正常结算时不该再补写消息树，否则同一条结果会出现两次", appended.isEmpty())
    }

    @Test
    fun `a failing append is not swallowed silently`() = runBlocking {
        // append 本身失败时不能再兜一层——否则等于把错误藏起来，用户看到的是
        // 「工具跑了但什么都没有」，比报错更难查。
        var caught: Throwable? = null
        try {
            runBlocking {
                settleToolResult(
                    operationId = null,
                    round = 0,
                    toolName = "base",
                    outcome = result("r4"),
                    settle = { _, _, _, _ -> },
                    appendToTree = { error("消息树写入失败") },
                    log = { _, _ -> },
                )
            }
        } catch (throwable: Throwable) {
            caught = throwable
        }
        assertTrue("写入失败必须冒泡", caught != null)
    }
}
