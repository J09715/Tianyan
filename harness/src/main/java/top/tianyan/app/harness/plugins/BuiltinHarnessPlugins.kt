package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.harness.ChatApi
import top.tianyan.app.harness.ResponsesApi
import top.tianyan.app.harness.AnthropicApi
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.workflow.DelayNodeExecutor
import top.tianyan.app.plugin.api.*

/** Real built-ins exercise the same registration path as compiled third-party contributions. */
fun builtinHarnessPlugins(): List<HarnessPlugin> = listOf(HarnessPlugin(
    descriptor = PluginDescriptor("tianyan.harness", "1.0.0", extensions = listOf(
        PluginExtension("runtime-facts", PluginExtensionPoint.TOOL),
        PluginExtension("chat", PluginExtensionPoint.PROVIDER),
        PluginExtension("responses", PluginExtensionPoint.PROVIDER),
        PluginExtension("anthropic", PluginExtensionPoint.PROVIDER),
        PluginExtension("delay", PluginExtensionPoint.WORKFLOW),
        PluginExtension("text-result", PluginExtensionPoint.WORKFLOW),
    )),
    tools = listOf(PluginTool("runtime-facts", "查看插件 SDK 和扩展范围，不执行命令。",
        Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject,
    ) { PluginToolOutput(true, "插件 SDK 1；编译时工具、背景资料、Provider 协议、工作流执行器扩展已装配。") }),
    providers = listOf(
        PluginProvider("chat", LlmApi.OPENAI_COMPLETIONS) { client, json -> ChatApi(client, json) },
        PluginProvider("responses", LlmApi.OPENAI_RESPONSES) { client, json -> ResponsesApi(client, json) },
        PluginProvider("anthropic", LlmApi.ANTHROPIC_MESSAGES) { client, json -> AnthropicApi(client, json) },
    ),
    workflows = listOf(PluginWorkflow("delay") { DelayNodeExecutor() }),
    nodes = listOf(PluginWorkflowNode("text-result", "将指定文字作为节点结果输出。",
        Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""").jsonObject,
    ) { request -> PluginToolOutput(true, request.arguments.getValue("text").jsonPrimitive.content) }),
))
