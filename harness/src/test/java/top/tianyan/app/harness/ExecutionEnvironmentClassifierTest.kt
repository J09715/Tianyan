package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.runtime.shell.CommandResult

/**
 * 命令失败双分类器（DSH denialSignatures/runnerFailureRules 借鉴项）：
 * 执行器异常签名归因 / PRoot 拒绝方言 / 普通命令失败不误判。
 */
class ExecutionEnvironmentClassifierTest {

    // ---- A 类：执行器异常 ----

    @Test
    fun `runtime not ready is classified through cause chain`() {
        val thrown = RuntimeException("wrapper", IllegalStateException("Linux runtime is not ready. Call initialize() first."))
        val failure = ExecutionEnvironmentClassifier.classify(thrown)
        assertNotNull(failure)
        assertTrue(failure!!.summary.contains("运行时尚未初始化"))
        assertTrue(failure.advice.contains("不要重试") || failure.advice.contains("等待"))
    }

    @Test
    fun `qemu unavailability is classified`() {
        val thrown = IllegalStateException("QEMU 兼容环境未就绪，请先安装并验证 qemu-x86-64-compat 插件")
        val failure = ExecutionEnvironmentClassifier.classify(thrown)
        assertNotNull(failure)
        assertTrue(failure!!.summary.contains("QEMU"))
    }

    @Test
    fun `proot launch failure is classified`() {
        val thrown = java.io.IOException("Cannot run program \"/data/.../proot\": error=13, Permission denied")
        val failure = ExecutionEnvironmentClassifier.classify(thrown)
        assertNotNull(failure)
        assertTrue(failure!!.summary.contains("PRoot"))
    }

    @Test
    fun `generic exception stays unclassified`() {
        assertNull(ExecutionEnvironmentClassifier.classify(IllegalArgumentException("命令过长")))
        assertNull(ExecutionEnvironmentClassifier.classify(RuntimeException("some random failure")))
    }

    @Test
    fun `classification result text forbids retry`() {
        val failure = ExecutionEnvironmentClassifier.classify(
            IllegalStateException("Linux runtime is not ready"),
        )!!
        val text = ExecutionEnvironmentClassifier.resultText(failure)
        assertTrue(text.contains("环境故障"))
        assertTrue(text.contains("与命令内容无关"))
    }

    // ---- C 类：PRoot 拒绝方言 ----

    @Test
    fun `proot stderr prefix is classified as denial`() {
        val result = CommandResult(
            exitCode = 1, stdout = "", stderr = "proot warning: can't sanitize binding /data/missing",
            durationMs = 10,
        )
        val failure = ExecutionEnvironmentClassifier.classifyDenial(result)
        assertNotNull(failure)
        assertTrue(failure!!.summary.contains("PRoot"))
    }

    @Test
    fun `multiline proot output with leading blank line is still classified`() {
        val result = CommandResult(
            exitCode = 255, stdout = "", stderr = "\nproot error: can't open the guest rootfs",
            durationMs = 5,
        )
        assertNotNull(ExecutionEnvironmentClassifier.classifyDenial(result))
    }

    @Test
    fun `ordinary command failure is not a denial`() {
        // 命令自身的错误输出（shell/工具的 stderr）不含 proot 前缀，不得误判
        assertNull(
            ExecutionEnvironmentClassifier.classifyDenial(
                CommandResult(127, "", "sh: foo: not found", 10),
            ),
        )
        assertNull(
            ExecutionEnvironmentClassifier.classifyDenial(
                CommandResult(1, "", "cat: /etc/missing: No such file or directory", 10),
            ),
        )
        // 成功结果永不归类
        assertNull(
            ExecutionEnvironmentClassifier.classifyDenial(
                CommandResult(0, "proot info: ok", "", 10),
            ),
        )
    }

    @Test
    fun `stderr mentioning proot mid-text without prefix is not a denial`() {
        // 用户脚本里恰好 echo 了 proot 字样：前缀语义守门
        assertNull(
            ExecutionEnvironmentClassifier.classifyDenial(
                CommandResult(1, "", "echo: proot something went wrong in my script", 10),
            ),
        )
    }

    // ---- 文案与安全边界 ----

    @Test
    fun `advice never claims the command was executed`() {
        val all = listOf(
            ExecutionEnvironmentClassifier.classify(IllegalStateException("Linux runtime is not ready")),
            ExecutionEnvironmentClassifier.classify(java.io.IOException("Cannot run program \"proot\"")),
            ExecutionEnvironmentClassifier.classifyDenial(
                CommandResult(1, "", "proot error: can't open the guest rootfs", 5),
            ),
        )
        for (failure in all.filterNotNull()) {
            val text = ExecutionEnvironmentClassifier.resultText(failure)
            assertFalse(text.contains("执行失败反思"))
            assertTrue(text.contains("不要重试") || text.contains("告知用户"))
        }
        assertEquals(3, all.filterNotNull().size)
    }
}
