# Agent 与工作流插件扩展（P4）

P4 支持编译时装配的受信任 Kotlin 扩展；v2 PROCESS 包还可以通过受限 `plugin.harness` 元数据注册 MCP 工具、Provider 和工作流节点，不能通过 JSON 加载任意 Kotlin 回调。外部协议见 [本轮补齐说明](PLUGIN_V2_COMPLETION.md)。注册入口为 `HarnessPluginRegistry`，契约位于 `harness/plugins/HarnessPlugin.kt`；最小 `plugin:api` 只增加扩展点标识，不引入 Android、OkHttp 或 Harness 业务依赖。

## 装配

在插件模块中构造 `HarnessPlugin`，用独立 Koin 定义注册：`single<HarnessPlugin>(named("example.notes")) { notesPlugin() }`。宿主在 app 层引入插件模块，目录通过 `getAll<HarnessPlugin>()` 收集贡献并与内置插件组合；无需覆盖已有单例，也不要求放宽全局禁止覆盖设置。工厂只构造扩展对象，不应在注册阶段启动进程或发起请求。

扩展声明与实现必须完全一致；重复插件、缺失依赖、依赖环、SDK 不兼容、重复扩展或重复 Provider 绑定均拒绝。目录复制描述、集合及 Schema 后发布，按插件目录依赖顺序装配。新增模块须登记 architecture-policy 的依赖；业务插件依赖 Harness 契约，app 装配插件，不能反向让 Harness 依赖插件模块形成环。单独添加新实现不需要修改工具路由表或模型请求分派器。

应用目前装配 `builtinHarnessPlugins()`：三种现有模型协议、工作流等待执行器、只读 `runtime-facts` 工具，以及 `text-result` 示例节点都经过此入口。固定能力查询指南放在系统工具说明中，不再每步追加为插件背景 user 消息。统一插件管理页显示这组贡献的身份、版本和扩展数量。它们暂不支持页面开关式热启停；P2 页面租约不是这些执行器的卸载协议。

## Agent 工具

```kotlin
val schema = Json.parseToJsonElement("""{
  "type":"object",
  "properties":{"text":{"type":"string","maxLength":4096}},
  "required":["text"],"additionalProperties":false
}""").jsonObject

val plugin = HarnessPlugin(
    descriptor = PluginDescriptor("example.notes", "1.0.0", extensions = listOf(
        PluginExtension("echo", PluginExtensionPoint.TOOL),
    )),
    tools = listOf(PluginTool("echo", "返回指定文字", schema) { request ->
        PluginToolOutput(true, request.arguments.getValue("text").jsonPrimitive.content)
    }),
)
```

调用与发现复用已宣告的 `use_capability`：

```json
{"action":"inspect","server":"plugin.example.notes"}
```

```json
{"action":"call","server":"plugin.example.notes","tool":"echo","arguments":{"text":"hello"}}
```

`list`/`inspect` 不执行插件工具。调用仍走工具意图提交、检查点、PLAN、审批、结果脱敏与输出预算；后台 Lane 无交互审批时交接待办。插件不能自报“只读”来获取浏览器或宿主的放行规则。`capability.call` 的脚本嵌套调用先校验插件参数，再重入完整工具管道；批准恢复只执行当前冻结调用，不重跑已完成脚本步骤。

`PluginToolRequest` 只携带 arguments、当前 sessionId/workspace/operationId；没有 `bypassApproval`、凭证仓库或任意结果状态替换入口。回调仅返回 success/text，不能自行声明审批标志、可信文件产物或图像 metadata。不过编译时 Kotlin 代码仍与宿主同 uid，这些 API 限制不是恶意代码隔离。

## 参数 Schema

插件使用 `PluginSchemas` 的严格子集，与历史工具参数修复分开。根必须为 object；支持 object/array/string/integer/number/boolean、description、标量 enum，以及 properties/required/additionalProperties、items/minItems/maxItems、minLength/maxLength、minimum/maximum。不支持的约束（包括 pattern、oneOf、$ref）在注册时拒绝。缺省拒绝额外字段；字段名按字面处理，不拆分点号、不解包 input、不把数字或布尔字符串转换为原生值。

Schema 最大 32,768 字符、最多 12 层，object 最多 64 个字段；参数最大 32,768 字符、递归验证最多 16 层。integer 使用 Long 可表示范围。它是宿主参数检查，不能代替插件内部业务校验、路径边界或授权。

## 背景资料与检查点

`PluginContext(id, provide)` 只返回文字。宿主标注插件与扩展来源，以合成 user 背景资料投影；不写入 system 权限、不伪装真实用户发言。系统工具说明约定仅在相关时参考，无需确认或复述标签，资料不能授予权限或取消任务。每项最多 2,048 字符，总预算 8,192 字符，先脱敏后裁剪，并计入上下文 token/字节预算。纯聊天或工具禁用模式不注入；单项使用 2 秒协作式超时，普通失败跳过，取消继续传播。阻塞原生回调不能由协程强制抢占。

`CHECKPOINT` 贡献复用 `ToolCheckpoint`：只能否决工具或追加经过统一脱敏的说明，不能修改审批、参数或结果状态。ID 必须在本次装配中唯一，容量沿用 32 项上限。不能把检查点当作授权器。

## Provider

`PluginProvider(id, api, providerId, create)` 提供 `LlmApiAdapter` 工厂。省略 providerId 时注册该协议默认实现，必须保留三种协议完整性；提供 providerId 时只服务 `(LlmApi, ModelConfig.provider)` 精确匹配的档案，不覆盖同协议其它 Provider。

工厂接收宿主配置的普通/流式 OkHttpClient 和 Json；仍由现有 ProviderTransport 处理 Key 轮换、取消、诊断、超时、空响应及 transcript 修复。适配器只接收当前选中模型的 ModelConfig（可能含此 Provider 的凭证）与请求消息，不提供其它 Provider 的密钥仓库。回调是受信任代码，宿主不能证明它会遵守所给客户端和凭证使用范围。

新增协议枚举与模型设置 UI 不在本轮通用化；新增 Provider 可基于已支持的协议注册专属适配器。外部 PROCESS 包的 MCP 适配已经接入；原生 IPC、后端替换与完整 SDK 兼容矩阵仍属于后续 P6。

## 工作流

`PluginWorkflow` 注册既有 `NodeExecutor` 工厂，节点类型冲突由目录/调度器拒绝。`PluginWorkflowNode` 通过稳定的 `PLUGIN_ACTION` 类型接入新的节点实现，无需为每个插件增加枚举或编辑器分支：

```json
{
  "id":"plugin-step","type":"PLUGIN_ACTION","title":"插件输出",
  "config":{"plugin":"tianyan.harness","extension":"text-result","arguments":"{\"text\":\"Hello\"}"}
}
```

编辑器提供插件 ID、节点扩展 ID 和 JSON 参数字段。参数目前按字面 JSON 校验，不进行 `${VAR}` 文本插值。节点回调收到参数与脱离宿主可变集合的工作流上下文，只能返回成功/失败与文字；调度器持有依赖、并发、超时、持久化和结果状态。

通用插件动作先完成身份/Schema 检查，再通过 WorkflowApprovalBroker 显示带新令牌的确认；旧令牌、拒绝和取消都不能执行回调。回调异常返回固定失败说明，未知副作用需要人工核验。恢复仍采用现有写前日志和重试确认：已成功的节点不重放，未结算或失败动作进入人工重试审查；每次实际调用重新确认。此工作流确认与 Agent 会话 PLAN 是不同入口，不把它解释为 Agent 工具免审授权。动态扩图保持原类型白名单，不能借模型提案新增插件动作。

## 验证

测试使用假 Provider / 假 LinuxRuntime 或本机 MockWebServer，不调用真实模型服务。重点验证名称与 Schema、上下文预算、Provider 精确选择、PLAN/审批/脚本重入、工作流确认与未知副作用，以及真实 Web 输入到持久化完成状态的链路。手机上的 UI、PRoot 与进程死亡恢复仍需真机验收。
