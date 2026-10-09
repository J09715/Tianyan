package top.tianyan.app.core.model

/**
 * 判断一个工具是否接受「云端模型档案注入」。
 *
 * 背景：工具详情页有一张「模型配置」卡片，点「应用」会把某个模型档案的
 * provider / baseUrl / model / apiKey 写进全局 provider 兜底，供该工具调用云端模型。
 *
 * 但这条规则不能只看分类。**本地推理引擎（llama.cpp）本身就是模型服务端**：
 * 它启动时只接受 `-m <GGUF 文件>`，从不读取 provider / baseUrl / apiKey。
 * 给它显示注入卡片是误导——用户会以为选个云端档案就能让本地引擎跑起来，
 * 而真正该做的是到「本地 LLM」页下载并启动 GGUF 模型。
 *
 * 抽成纯逻辑是为了让这条判断可被单测覆盖：它此前内联在 UI 里，
 * 改分类或加新工具时很容易漏掉这个例外。
 */
object ToolModelInjection {

    /** 会调用云端模型的工具分类。 */
    private val REMOTE_MODEL_CATEGORIES = setOf("AI_AGENT", "CODING_AGENT")

    /** 本地推理引擎：自己就是模型服务端，不接受云端档案注入。 */
    val LOCAL_INFERENCE_TOOL_IDS = setOf("llama-cpp")

    /**
     * 是否显示「模型配置」注入卡片。
     *
     * @param toolId 工具 id（用于识别本地推理引擎这类例外）
     * @param category 工具分类
     */
    fun acceptsRemoteModelInjection(toolId: String, category: String?): Boolean {
        if (toolId.lowercase() in LOCAL_INFERENCE_TOOL_IDS) return false
        return category in REMOTE_MODEL_CATEGORIES
    }
}
