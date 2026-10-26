# Responses 原生历史回放

Responses API 的手动上下文需要保留模型的完整输出项。普通正文和推理摘要无法替代原始 reasoning 项，原始消息中的 `phase` 也影响模型对中间进度和最终答复的理解。协议依据：[OpenAI conversation state](https://developers.openai.com/api/docs/guides/conversation-state)。

天衍在 `AssistantText.responsesTurn` 中保存 Responses 的完整 `output`，包括 item ID、函数调用 ID、消息 phase 和不透明的 `encrypted_content`。它随现有 Room `payloadJson` 保存，无数据库表结构迁移；旧消息没有该字段时按普通历史读取。只有工具调用的回合也保存空正文 assistant 条目，作为原生输出的锚点。

`ResponsesApi` 从非流式最终响应或 SSE `response.completed` 获取输出。网关省略完成事件里的 output 时，可以使用带连续 output_index 的 `output_item.done`；`output_item.added` 中的部分加密内容不会保存。没有完成事件的流式中断只保留已有正文。仅在完成事件提供正文或工具调用的网关也能正常工作，已有正文增量不会重复发送到 UI。

主会话和子智能体通过 `ApiMessageProjector` 使用同一回放校验，再由 `ResponsesRequestBuilder` 构建 input。满足以下条件时，才原样回放整个输出组：

- Responses 模式开启，供应商、端点、模型和凭据范围相同。凭据范围只保存 API key 与自定义请求头的 SHA-256 摘要，不保存明文凭据。
- 保存的原生正文与当前投影正文一致，函数名称和 JSON 参数一致；函数调用 ID 有效且不重复。
- 所有工具调用都有实际完成的工具结果；缺失、等待审批、被上下文裁剪的调用不带入整组原生输出。
- 输出类型属于 reasoning、message、function_call，且没有未完成的输出项。本轮不支持的托管工具输出使用普通历史回退。
- 同一请求中原生 item ID 和调用 ID 不与此前原生回合重复。

本地工具调用 ID 经规范化后可能与 provider 的 call_id 不同，构建请求时会按名称和参数匹配，并把对应工具结果映射回原始 call_id。映射只作用于当前 assistant 回合。工具结果的到达顺序可以不同。

校验失败时保留普通正文、函数调用和结果，不伪造 reasoning 项。编辑回复、切换模型或凭据、部分工具批次、中断和上下文裁剪都可以触发这一回退。Chat Completions 和其他协议不会发送该原生附加数据；JSON 文本工具模式也不使用它。

每轮原生输出上限为 256,000 UTF-8 字节，超过时不保存。上下文估算将原生载荷加入 token 与请求体预算，文本和参数可能被重复估算，以保守控制体积。请求体仍超限时可先省略原生附加数据而保留普通历史，数据库中的原始记录不受影响。加密内容不会显示在聊天或请求诊断页；诊断预览继续省略 encrypted_content、signature 等不透明字段。

回归覆盖：原生字段与输出顺序、规范化 ID 与乱序工具结果、旧消息兼容、Room 重新打开、主会话/子智能体一致性、编辑与切换回退、缺失/待审批结果、完成事件补全文本、部分加密内容丢弃、EOF/索引缺口、载荷体积预算。测试使用本地 MockWebServer 和 Robolectric，未调用真实模型服务。
