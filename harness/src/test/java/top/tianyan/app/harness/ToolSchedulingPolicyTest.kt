package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合级并发判定：子智能体不再无条件屏障，而是按声明的 write_paths 是否相交给出并发资格。
 * 判定口径必须与编排层 buildWriteCleanWaves 一致（同用 normalizeWritePath/writePathsConflict）。
 */
class ToolSchedulingPolicyTest {

    private fun subagentArgs(vararg writePaths: String): JsonObject = buildJsonObject {
        put(
            "subagents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("taskName", JsonPrimitive("task"))
                        put("department", JsonPrimitive("engineering"))
                        put("agentQuery", JsonPrimitive("kotlin test"))
                        put("prompt", JsonPrimitive("do the thing"))
                        put(
                            "writePaths",
                            buildJsonArray { writePaths.forEach { add(JsonPrimitive(it)) } },
                        )
                    },
                )
            },
        )
    }

    @Test
    fun `read only tools keep running in parallel`() {
        assertTrue(ToolSchedulingPolicy.isParallelSafe(HarnessTool.READ))
        assertTrue(ToolSchedulingPolicy.isParallelSafe(HarnessTool.LOAD_RULE))
    }

    @Test
    fun `mutating tools remain barriers`() {
        assertFalse(ToolSchedulingPolicy.isParallelSafe(HarnessTool.WRITE))
    }

    @Test
    fun `subagent with read only intent is parallel safe and declares no paths`() {
        val args = subagentArgs()
        assertTrue(ToolSchedulingPolicy.isParallelSafe(HarnessTool.SUBAGENT, args))
        assertEquals(emptySet<String>(), ToolSchedulingPolicy.subagentWritePaths(args))
    }

    @Test
    fun `malformed subagent args fall back to a barrier`() {
        val args = buildJsonObject { put("subagents", JsonPrimitive("not-an-object")) }
        assertNull(ToolSchedulingPolicy.subagentWritePaths(args))
        assertFalse(ToolSchedulingPolicy.isParallelSafe(HarnessTool.SUBAGENT, args))
        // 解析不出来时组内判定也必须保守：宁可串行，不要猜。
        assertTrue(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                args,
                HarnessTool.SUBAGENT,
                subagentArgs("harness/a.kt"),
            ),
        )
    }

    @Test
    fun `disjoint write sets may share a concurrency group`() {
        val left = subagentArgs("harness/a.kt")
        val right = subagentArgs("feature/b.kt")

        assertTrue(ToolSchedulingPolicy.isParallelSafe(HarnessTool.SUBAGENT, left))
        assertFalse(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, left, HarnessTool.SUBAGENT, right),
        )
    }

    @Test
    fun `overlapping write sets conflict`() {
        val left = subagentArgs("harness/a.kt")
        val same = subagentArgs("harness/a.kt")
        val parent = subagentArgs("harness")

        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, left, HarnessTool.SUBAGENT, same),
        )
        // 父子目录同样算冲突，与 writePathsConflict 的语义一致。
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, left, HarnessTool.SUBAGENT, parent),
        )
    }

    @Test
    fun `whole workspace lease conflicts with every other batch`() {
        val whole = subagentArgs("*")
        val scoped = subagentArgs("harness/a.kt")
        val readOnly = subagentArgs()

        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, whole, HarnessTool.SUBAGENT, scoped),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, whole, HarnessTool.SUBAGENT, readOnly),
        )
    }

    @Test
    fun `read only batches never conflict with each other`() {
        assertFalse(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                subagentArgs(),
                HarnessTool.SUBAGENT,
                subagentArgs(),
            ),
        )
    }

    @Test
    fun `writing subagent batch never shares a group with a read only tool`() {
        val writing = subagentArgs("harness/a.kt")

        // 两个方向都要断：先 read 后 invoke_subagent，写入同样会落在读到的文件上。
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, writing, HarnessTool.READ, null),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.READ, null, HarnessTool.SUBAGENT, writing),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                subagentArgs("*"),
                HarnessTool.HISTORY_READ,
                null,
            ),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.LOAD_RULE,
                null,
                HarnessTool.SUBAGENT,
                subagentArgs("harness"),
            ),
        )
        // 只读工具之间仍可同组，否则并发退化成串行。
        assertFalse(
            ToolSchedulingPolicy.conflicts(HarnessTool.READ, null, HarnessTool.LOAD_RULE, null),
        )
    }

    @Test
    fun `read only batch conflicts with writing batch`() {
        val readOnly = subagentArgs()
        val writing = subagentArgs("harness/a.kt")

        // 只读子任务照样会 read：与写批次并发可能读到半成品，两个方向都要断组。
        assertTrue(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                readOnly,
                HarnessTool.SUBAGENT,
                writing,
            ),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                writing,
                HarnessTool.SUBAGENT,
                readOnly,
            ),
        )
        // 只读批次之间仍可同组：双方都空必须继续短路为不冲突。
        assertFalse(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                readOnly,
                HarnessTool.SUBAGENT,
                subagentArgs(),
            ),
        )
    }

    @Test
    fun `read only subagent batch may share a group with read only tools`() {
        val readOnlyBatch = subagentArgs()

        assertFalse(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.SUBAGENT,
                readOnlyBatch,
                HarnessTool.READ,
                null,
            ),
        )
        assertFalse(
            ToolSchedulingPolicy.conflicts(
                HarnessTool.READ,
                null,
                HarnessTool.SUBAGENT,
                readOnlyBatch,
            ),
        )
    }

    @Test
    fun `unparsable subagent args block read only neighbours`() {
        val malformed = buildJsonObject { put("subagents", JsonPrimitive("not-an-object")) }

        // 去向未知时按"会写"处理：让只读工具多等一组，也不要赌它不落盘。
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.SUBAGENT, malformed, HarnessTool.READ, null),
        )
        assertTrue(
            ToolSchedulingPolicy.conflicts(HarnessTool.READ, null, HarnessTool.SUBAGENT, malformed),
        )
    }
}
