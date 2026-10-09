package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 本地推理上下文窗口的决策规则。
 *
 * 这条逻辑出过一个用户可感知的缺陷：模型配置里把上下文调到 64000，
 * 服务端仍以 4096 启动，稍长的对话直接
 * HTTP 400「request (14317 tokens) exceeds the available context size (4096 tokens)」。
 * 原因是 `--ctx-size` 只按设备内存硬编码，完全忽略了配置值。
 * 这里把「配置优先」和「边界收敛」两条规则钉住。
 */
class LocalLlmContextTest {

    private val lowRam = 4L * 1024 * 1024 * 1024   // 4 GiB
    private val normalRam = 8L * 1024 * 1024 * 1024 // 8 GiB

    /** 核心回归：用户配置必须生效，不能被设备内存推断覆盖。 */
    @Test
    fun `configured value wins over device memory fallback`() {
        assertEquals(64_000, LocalLlmContext.resolve(64_000, normalRam))
        assertEquals(32_768, LocalLlmContext.resolve(32_768, normalRam))
        // 低内存设备上配置了更大值也应生效：用户明确要求优先于推断。
        assertEquals(16_384, LocalLlmContext.resolve(16_384, lowRam))
    }

    /** 未配置时按设备内存推断：低内存给 2048，常规给 4096。 */
    @Test
    fun `unconfigured falls back to device memory heuristic`() {
        assertEquals(LocalLlmContext.LOW_MEMORY_CONTEXT, LocalLlmContext.resolve(null, lowRam))
        assertEquals(LocalLlmContext.DEFAULT_CONTEXT, LocalLlmContext.resolve(null, normalRam))
    }

    /** 0 / 负数视为「未配置」而不是「收敛到下限」——无效输入不该被当成一个具体上限。 */
    @Test
    fun `non-positive values are treated as unset`() {
        assertEquals(LocalLlmContext.DEFAULT_CONTEXT, LocalLlmContext.resolve(0, normalRam))
        assertEquals(LocalLlmContext.DEFAULT_CONTEXT, LocalLlmContext.resolve(-1, normalRam))
        assertEquals(LocalLlmContext.LOW_MEMORY_CONTEXT, LocalLlmContext.resolve(0, lowRam))
    }

    /** 过小的值收敛到下限：低于它连系统提示词加一轮工具输出都放不下。 */
    @Test
    fun `too small values are clamped up`() {
        assertEquals(LocalLlmContext.MIN_CONTEXT, LocalLlmContext.resolve(1, normalRam))
        assertEquals(LocalLlmContext.MIN_CONTEXT, LocalLlmContext.resolve(512, normalRam))
    }

    /** 过大的值收敛到上限：llama.cpp 按 ctx 预分配 KV cache，手机上会 OOM。 */
    @Test
    fun `too large values are clamped down`() {
        assertEquals(LocalLlmContext.MAX_CONTEXT, LocalLlmContext.resolve(1_000_000, normalRam))
        assertEquals(LocalLlmContext.MAX_CONTEXT, LocalLlmContext.resolve(Int.MAX_VALUE, normalRam))
    }

    /** 边界值本身应原样通过（闭区间）。 */
    @Test
    fun `boundary values pass through unchanged`() {
        assertEquals(LocalLlmContext.MIN_CONTEXT, LocalLlmContext.resolve(LocalLlmContext.MIN_CONTEXT, normalRam))
        assertEquals(LocalLlmContext.MAX_CONTEXT, LocalLlmContext.resolve(LocalLlmContext.MAX_CONTEXT, normalRam))
    }

    /** 结果永远落在合法区间内，任何输入都不会漏出去。 */
    @Test
    fun `result is always within bounds`() {
        val inputs = listOf(null, 0, -5, 1, 2048, 4096, 8192, 65_536, 131_072, 999_999, Int.MAX_VALUE)
        val rams = listOf(0L, lowRam, normalRam, 64L * 1024 * 1024 * 1024)
        inputs.forEach { ctx ->
            rams.forEach { ram ->
                val v = LocalLlmContext.resolve(ctx, ram)
                kotlin.test.assertTrue(
                    v in LocalLlmContext.MIN_CONTEXT..LocalLlmContext.MAX_CONTEXT,
                    "resolve($ctx, $ram) = $v 越界",
                )
            }
        }
    }
}
