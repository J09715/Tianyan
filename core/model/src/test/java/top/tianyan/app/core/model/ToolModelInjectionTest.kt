package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「模型配置」注入卡片的显示规则。
 *
 * 这条判断此前内联在 UI 里，只看分类：
 *   if (category == "AI_AGENT" || category == "CODING_AGENT")
 * 而 llama-cpp 的分类正是 AI_AGENT —— 于是本地推理引擎也被显示「注入云端模型档案」的卡片，
 * 但它的模型是本地 GGUF 文件，启动命令只吃 `-m <路径>`，从不读 provider/baseUrl/apiKey。
 * 用户按提示选了云端档案点「应用」，本地引擎不会有任何变化。
 */
class ToolModelInjectionTest {

    @Test
    fun `remote-capable agents accept injection`() {
        assertTrue(ToolModelInjection.acceptsRemoteModelInjection("claude-code", "CODING_AGENT"))
        assertTrue(ToolModelInjection.acceptsRemoteModelInjection("openclaw", "AI_AGENT"))
        assertTrue(ToolModelInjection.acceptsRemoteModelInjection("hermes-agent", "AI_AGENT"))
    }

    /** 核心回归：本地推理引擎不接受云端档案注入。 */
    @Test
    fun `local inference engine never accepts injection`() {
        assertFalse(
            ToolModelInjection.acceptsRemoteModelInjection("llama-cpp", "AI_AGENT"),
            "llama-cpp 是模型服务端，只吃 -m <GGUF>，注入云端档案对它无效",
        )
        // 即便分类被改成别的 AI 类，也必须仍然排除。
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("llama-cpp", "CODING_AGENT"))
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("llama-cpp", "AI_AGENT"))
    }

    /** 大小写不敏感：注册表里的 id 大小写差异不该让例外失效。 */
    @Test
    fun `local engine id match is case-insensitive`() {
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("LLAMA-CPP", "AI_AGENT"))
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("Llama-Cpp", "AI_AGENT"))
    }

    /** 纯开发工具本就不需要模型注入。 */
    @Test
    fun `developer tools never accept injection`() {
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("qemu-x86-64-compat", "DEVELOPER_TOOL"))
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("hello-tool", "DEVELOPER_TOOL"))
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("android-suite", null))
        assertFalse(ToolModelInjection.acceptsRemoteModelInjection("x", ""))
    }

    /** 例外清单必须包含 llama-cpp——它是当前唯一的本地推理引擎。 */
    @Test
    fun `local inference set covers the shipped engine`() {
        assertTrue(
            "llama-cpp" in ToolModelInjection.LOCAL_INFERENCE_TOOL_IDS,
            "本地推理引擎清单缺少 llama-cpp，注入卡片会重新出现",
        )
    }
}
