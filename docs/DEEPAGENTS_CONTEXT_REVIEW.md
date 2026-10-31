# deepagents 上下文治理对照与改进清单

核验日期：2026-10-16。初始评审基线：工作区源码，HEAD `156942f06d53f29563c4701a019bc113f4c85d4a`；上游引用为核验时的 `main`，不是固定发布版本。下面的差距分析记录实施前的状态；后续落实情况见本节。不调用真实付费模型。

## 当前清单状态（2026-10-17）

| 清单项 | 当前状态与验收边界 |
| --- | --- |
| P1 历史分页回读 | 已完成：脱敏后分页，支持读取 48K 后事实，稳定消息 ID 与关联组续读 |
| P1 搜索 index 契约 | 已完成：活动分支原始消息索引，优先使用 message_id |
| P1 历史 write/edit 大参数缩减 | 已完成：保留保护窗口与原始历史，三种协议 × 两种工具模式验证最终请求 |
| P1 压缩后继续任务 | 已完成链路验收：历史分页、有效产物分页、审批批准/拒绝、产物过期/变更/写入失败及 Web 完成事件 |
| P2 预算与诊断 | 已分阶段落地：背景预算分配、来源/动作/保护原因、序列化体积与发送门禁；历史折叠等仍使用既有分阶段策略，未扩展为全部来源的统一价值排序 |
| P2 引用与媒体边界 | 已明确并实现核验/失效反馈；继续有界清理，不固定保留活跃会话产物，history_read 不恢复图片载荷 |

## 首批实施状态

- 已补历史分页：默认 8,000、最大 16,000 UTF-16 字符，offset 从 0 开始；返回稳定消息 ID、total_chars、truncated、next_offset。关联调用与结果按同一文本顺序分页，原文先脱敏再切片。工具管道不再二次折叠有界历史页的长单行。分页针对脱敏后的文本，切换分支或脱敏配置后应重新定位并从头读取。
- 已统一 index：搜索结果使用活动分支的原始消息索引，优先使用 message_id；schema 与系统提示说明一致。
- 已补历史大参数缩减：[HistoricalToolArguments.kt](../harness/src/main/java/top/tianyan/app/harness/HistoricalToolArguments.kt) 仅缩减旧轮次已成功完成的 write.content / edit.oldText / edit.newText 大字符串（超过 2,048 字符），保留首尾、原长和回读引用。当前轮、最近四个结果关联调用、待审批、失败、延后审批和无结果调用保持原样。非字符串和未知字段保持原样；未覆盖旧别名或包装参数。
- 主请求、用量估算与自动摘要 replay 前缀复用同一缩减投影；不改原始历史或执行参数。参数变化使不再匹配的 Responses 原生 replay 失效，转为缩减后的普通消息投影。
- 新增[分页测试](../harness/src/test/java/top/tianyan/app/harness/HistoryReadPageTest.kt)、[参数投影测试](../harness/src/test/java/top/tianyan/app/harness/HistoricalToolArgumentsTest.kt)、[最终 HTTP 请求协议测试](../harness/src/test/java/top/tianyan/app/harness/ContextProjectionProtocolTest.kt)与[压缩后恢复测试](../harness/src/test/java/top/tianyan/app/harness/HistoryContextRecoveryTest.kt)。协议测试只访问本地 MockWebServer，覆盖三种 Provider 协议与 NATIVE / JSON_TEXT 两种工具模式。
- 首批恢复测试使用真实 Room、重建后的会话适配器、AgentTurnRunner 和 ToolExecutor：机械摘要遗漏 48K 之后的事实，假模型通过搜索与连续分页补回事实并完成答复，不重新执行原读取动作。完整 HarnessLoop / Web 覆盖与后续治理诊断见下一节。
- 验证：上述新增测试与 BuiltinToolContract、ApiContextAssembler、ContextWindowPolicy、ResponsesHistoryReplay、HarnessCommandOutput、ToolOutputRetention 定向回归共 122 项通过；`architectureCheck` 通过（36 个模块）。测试使用离线 Gradle 依赖与本地假模型，无真实模型请求；未运行全仓库测试或真机验证。

## 后续实施：完整 Web 链路与治理诊断

- 新增 [ContextRecoveryWebEndToEndTest.kt](../app/src/test/java/top/tianyan/app/webchat/ContextRecoveryWebEndToEndTest.kt)：真实认证 REST 输入 → 共享 HarnessLoop → 假 Provider → 历史搜索/分页回读 → 插件工具审批 → 批准或拒绝 → 恢复模型回合 → 真实 SSE completed 事件 → REST 读取已提交的最终消息。两条路径分别核对批准后执行一次、拒绝后执行零次；历史工具调用不重新执行。测试不访问外部模型网络，也不使用真实 Linux 或宿主特权副作用。
- 新增 [ContextGovernanceTrace.kt](../harness/src/main/java/top/tianyan/app/harness/diagnostics/ContextGovernanceTrace.kt)：每次组装记录来源贡献、治理阶段、变更类别、消息与条目来源及前后文本估算；至多保留 32 条变更明细，额外变更保留计数，不复制正文或参数。
- `ApiContextAssembler` 沿现有执行顺序记录治理动作；`HarnessProviderRunner` 在实际请求捕获时附上对应组装报告。重新组装重置报告，网络重试复用当前视图，不使用会话级“最近报告”缓存，避免并行会话或轮次串线。
- 报告作为“最近请求上下文”里的 `context_governance (local)` 字段，复用既有脱敏、字段截短、加密存档与删除边界，不进入模型请求或历史执行指令。新增 [ContextGovernanceTraceTest.kt](../harness/src/test/java/top/tianyan/app/harness/diagnostics/ContextGovernanceTraceTest.kt) 验证真实缩减结果、来源、容量、重组装隔离、凭据移除和存档恢复。
- 本阶段提供变更诊断，尚未引入统一的上下文价值排序/预算分配策略、逐条未变更保护原因或产物引用保留/失效管理。计数口径与限制见 [CONTEXT_DIAGNOSTICS.md](CONTEXT_DIAGNOSTICS.md)。
- 验证：本轮 Harness 定向回归 120 项、App 端到端与 DI 回归 6 项均通过；架构检查通过。覆盖诊断脱敏/存档、历史治理、三种 Provider 协议、完整审批批准/拒绝及真实 Web SSE；未运行全仓库或真机回归。

## 复查修复：合成数据复现的三处缺陷与同类补漏

复查用离线合成数据确认三处实现缺口并已修复；修复后同类别补充排查另发现一处同源缺陷（第 4 条），一并修复（2026-10-16，假 Provider，无真实模型请求）：

1. **跨消息密钥回显漏脱敏**：[HistoryReadPage.render](../harness/src/main/java/top/tianyan/app/harness/HistoryReadPage.kt) 原先逐条消息脱敏，而 `SecretRedactor` 仅在单次入参内学习带标签密钥的回显；调用参数中的密钥线索先被抹掉后，关联结果里的裸回显无法识别，后续整页脱敏也因线索消失无法补救。现先拼接完整关联文本做单次脱敏，再按 UTF-16 切片分页；offset/total_chars 仍为脱敏后文本口径，跨页稳定。
2. **续读 ID 切换内容集合**：[SessionTreeStore.readWithRelated](../harness/src/main/java/top/tianyan/app/harness/session/SessionTreeStore.kt) 原先对结果锚点只返回该结果与调用两条，而渲染头部返回组内首条消息 ID（分支顺序即调用 ID），续页切到调用锚点后会扩展为全部关联结果，原 offset 对应文本随之改变。现两种锚点统一解析为调用根分组（调用 + 其全部结果，含待审批占位与最终结算），同一分组的文本与偏移不再随入口变化。
3. **已批准成功的调用永久跳过参数缩减**：[HistoricalToolArguments](../harness/src/main/java/top/tianyan/app/harness/HistoricalToolArguments.kt) 原先要求组内全部结果均成功且非待审批；审批暂停留下的旧占位记录会让已结算成功的调用永不缩减。现按组内最新一条记录的有效结算状态判定：最新为成功才缩减；末条仍待审批/延后或失败保持原样（未决审批继续受保护）。
4. **搜索命中预览单条作用域漏脱敏**（同类补充排查发现）：[HistoryReadPage.preview](../harness/src/main/java/top/tianyan/app/harness/HistoryReadPage.kt) 原先只对命中消息单条文本脱敏，与第 1 条同源：命中工具结果时标签只存在于同组调用消息里，裸回显无法被学习。现 preview 与 render 共用同一分组拼接布局（`groupText`），对完整关联组单遍脱敏后切出目标消息段再截 240 字符；[ContextMemoryToolBackend.executeHistorySearch](../harness/src/main/java/top/tianyan/app/harness/ContextMemoryToolBackend.kt) 逐命中经 `readWithRelated` 取组传入（分支解码有 SoftReference 缓存，逐命中取组不产生额外解码）。

新增回归：[HistoryReadPageTest](../harness/src/test/java/top/tianyan/app/harness/HistoryReadPageTest.kt) 跨消息回显脱敏的连续分页、搜索预览从关联组学习回显标签；[SessionMessageProjectorTest](../harness/src/test/java/top/tianyan/app/harness/projection/SessionMessageProjectorTest.kt) 待审批占位 + 最终结果下两种锚点分组一致；[HistoricalToolArgumentsTest](../harness/src/test/java/top/tianyan/app/harness/HistoricalToolArgumentsTest.kt) 已结算组缩减与未决/失败尾部保护。

验证：harness 全模块 1553 项通过（0 失败/0 错误）；App 端到端 [ContextRecoveryWebEndToEndTest](../app/src/test/java/top/tianyan/app/webchat/ContextRecoveryWebEndToEndTest.kt) 2 项通过；`architectureCheck` 本次唯一违规为他方并行改动 [GitScreen.kt](../feature/git/src/main/java/top/tianyan/app/ui/git/GitScreen.kt) 超出该文件行数棘轮基线，与本次修复文件无关。修复后"首批实施状态"中"原文先脱敏再切片""关联调用与结果按同一文本顺序分页""返回稳定消息 ID"的承诺成立；未运行全仓库或真机回归。

## 中断恢复后的补齐（2026-10-17）

- **预算决策**：新增纯函数 [ContextBudgetPlanner.kt](../harness/src/main/java/top/tianyan/app/harness/ContextBudgetPlanner.kt)，扣除 system、滚动摘要、输出和工具预留后，为当前任务保留空间；失败记忆、从新到旧的召回、插件背景依次分配，背景总额最多占剩余消息预算的四分之一。超额背景整块省略，避免切断来源和低权威包装；不改持久化召回或原始历史。压缩关闭时不启用此背景裁减；压缩后重新分配。历史折叠、媒体和字节兜底继续由既有策略执行，这不是全来源的最优排序器。
- **保护与体积诊断**：历史参数投影与诊断共用 `HistoryProtectionPolicy`，记录当前轮、最近结果、未应答、待审批、延后审批和失败的保护原因；保护明细最多 32 条，超额计数。报告新增按来源汇总的预算分配、最终序列化字节数及其与消息层估算的差值。差值包含工具定义和协议包装等影响，不是 token 估算误差。
- **最终请求门禁**：[ProviderRequestBudget.kt](../harness/src/main/java/top/tianyan/app/harness/ProviderRequestBudget.kt) 在普通和流式 HTTP 客户端检查序列化请求体，超过 4 MiB 抛出上下文溢出异常，沿已有有界恢复路径处理；无法确定体积时拒绝发送。三种协议 × 两种工具模式验证 JSON 转义膨胀请求在网络发送前被拒绝。此上限不代表服务端 token 窗口或所有网关都会接受请求。
- **引用生命周期**：继续采用既有 7 天、64 MiB、256 文件清理策略，不为活跃会话无限保留。`history_read` 从既有产物元数据返回路径、SHA-256、字节数和完整性，并标为 UNVERIFIED。`read(expected_sha256=...)` 在当前执行环境读取前后核验指纹，分别返回 VERIFIED / CHANGED / UNAVAILABLE；变更或无法核验时不交付读取正文，不回退宿主同名文件，也不自动重放旧操作。前后核验不是文件锁或原子快照，VERIFIED 也不表示原始命令输出完整。
- **媒体边界**：历史用户图片和工具图片明确标为 NOT_RESTORED，不返回内联图片或私有 URI；文本回读不恢复视觉证据。需要原图时须重新提供，或在原文件仍可访问时由当前环境重新读取。
- **异常链路**：Web 端到端用例增加真实产物过期清理与真实目录写入失败，均经过压缩、历史检索回读和 SSE completed；断言报告证据缺失、不编造尾部事实、不重放历史读取、不执行后续副作用。
- **恢复时修复**：端到端测试使用 `/workspace/<测试目录名>` 绑定会话，物理目录只用于准备产物，避免把 Windows 盘符路径当成沙箱路径；失败断言附带 Harness 的会话错误。补充组装器集成测试，验证超额召回只在请求投影中省略、持久化文本与召回不变；补充不支持指纹的后端不执行未核验读取的测试。
- **继续复查补验**：新增有效产物与版本变更两条 Web 端到端用例。有效产物的事实位于 48K 后，模型从历史引用取得路径/哈希，以 offset=1/201/401/601/801 连续核验读取 5 页后找回事实，再经审批完成且动作只执行一次。版本变更返回 CHANGED，替换正文不进入历史工具结果，最终报告证据缺失且无后续副作用；两者均不重放原始 `read(report.log)`。

验证（2026-10-17）：`architectureCheck` 36 个模块通过；Harness 全模块共 1568 项，1567 通过、1 跳过、0 失败/错误（Windows 缺少创建符号链接权限，跳过既有链接越界用例）；App Web 端到端 4 项与 DI 6 项全部通过；`git diff --check` 通过。最终命令为 `architectureCheck :harness:testDebugUnitTest :app:testDebugUnitTest --tests '*ContextRecoveryWebEndToEndTest' --tests 'top.tianyan.app.di.*' --offline --console=plain`，结果 `BUILD SUCCESSFUL`。补充的组装器/产物核验定向 30 项也通过。测试仅用假 Provider、本地服务及离线依赖，无真实付费模型调用；未运行全仓库或真机回归，未打包 APK。

本节为当前实现状态；前面的首批/后续实施与下面的“确认缺口”保留为实施前评审记录。

继续复查验证（2026-10-17）：`architectureCheck`、Harness 组装器/产物核验定向 30 项与扩展后的 Web 端到端 6 项全部通过（0 失败/错误），`BUILD SUCCESSFUL`；本轮仅新增链路测试与清单状态说明，未重复 Harness 全模块或真机回归。

## 结论

天衍已经具备“输出外置 → 老结果缩减 → 历史摘要 → 溢出恢复”的分层链路，以及技能按需加载、独立子任务与原文检索。历史分页与大参数治理已落地，本轮补充背景预算分配、保护原因诊断、最终请求体积门禁与引用失效反馈。媒体恢复目前明确限定为文本回读，产物保留仍受既有清理上限约束；无需为借鉴 deepagents 引入 Python/LangGraph 依赖或重写 Agent 循环。

## 上游依据

- [summarization.py](https://github.com/langchain-ai/deepagents/blob/main/libs/deepagents/deepagents/middleware/summarization.py)：`_truncate_args` 在摘要前缩减保留窗口之外的 `write_file` / `edit_file` 参数；修改后重新计数。`_count_tokens` 将 system 和 tools 纳入计数（计数器支持 tools 时）。自动与主动压缩复用摘要引擎，历史和内联媒体可外置。
- [_message_eviction.py](https://github.com/langchain-ai/deepagents/blob/main/libs/deepagents/deepagents/middleware/_message_eviction.py)：大结果替换为预览与可回读路径，保留消息身份字段；该辅助函数写入失败时返回空值，要求调用方保留原消息。
- [Subagents](https://docs.langchain.com/oss/python/deepagents/subagents) 与 [Skills](https://docs.langchain.com/oss/python/deepagents/skills)：上下文隔离与按需加载的参考。它们在天衍中属于已有方向，不列为待新增能力。

以上说明具体参考点，不推断上游所有错误路径均无损。上游文档注释中的触发顺序描述也不能替代实际配置：当前有模型 profile 时，参数缩减与摘要默认触发比例同为 0.85，但参数缩减先执行。

## 已有能力与边界

| 能力 | 天衍源码依据 | 判断 |
| --- | --- | --- |
| 工具输出落盘与分页回读 | [ToolExecutor.kt](../harness/src/main/java/top/tianyan/app/harness/ToolExecutor.kt)、[ToolOutputSpillStore.kt](../harness/src/main/java/top/tianyan/app/harness/ToolOutputSpillStore.kt) | 已有；正文附相对路径，存储受时间、文件数和容量约束，写入可能失败 |
| 摘要前缩减历史工具结果 | [ContextWindowPolicy.kt](../harness/src/main/java/top/tianyan/app/harness/ContextWindowPolicy.kt) 的 `truncateStaleToolResults` | 已有；保护当前用户轮与最近结果，缩减正文并附消息 ID |
| 自动与手动压缩 | [ApiContextAssembler.kt](../harness/src/main/java/top/tianyan/app/harness/session/ApiContextAssembler.kt)、[ContextMemoryToolBackend.kt](../harness/src/main/java/top/tianyan/app/harness/ContextMemoryToolBackend.kt) | 已有；共用 `CompactionManager`，手动入口要求用户明确提出 |
| 原文保留与并发保护 | [CompactionManager.kt](../harness/src/main/java/top/tianyan/app/harness/compaction/CompactionManager.kt)、[SessionProjection.kt](../harness/core/src/main/java/top/tianyan/app/harness/core/SessionProjection.kt) | 已有；不可变压缩条目、锁内前缀对账和水位线恢复 |
| 结构化滚动摘要 | [CompactionSummarizer.kt](../harness/src/main/java/top/tianyan/app/harness/compaction/CompactionSummarizer.kt) | 已有；目标、约束、进度、决策、下一步及文件足迹；LLM 失败可机械回退 |
| 技能渐进加载与稳定前缀 | [SystemPromptBuilder.kt](../harness/src/main/java/top/tianyan/app/harness/prompt/SystemPromptBuilder.kt)、ApiContextAssembler | 已有；技能目录与 `load_skill` 分离，动态召回按用户轮持久化 |
| Token 与字节预算、溢出恢复 | ContextWindowPolicy、[ProviderContextRecovery.kt](../harness/src/main/java/top/tianyan/app/harness/ProviderContextRecovery.kt)、ProviderRequestBudget | 已有多阶段估算与恢复；本轮增加内置协议序列化体积的发送前 4 MiB 门禁 |
| 请求诊断 | [CONTEXT_DIAGNOSTICS.md](CONTEXT_DIAGNOSTICS.md) | 已有最终请求预览、字段差异及有界来源/治理动作/保护原因/预算分配追踪；详见后续实施与中断恢复后的补齐 |

## 优先改进项

### P1：让历史回读真正可续读

**确认缺口**：`ContextMemoryToolBackend.executeHistoryRead` 只接受 `message_id/index`。消息格式化和最终拼接均使用 `take(MAX_HISTORY_READ_OUTPUT)`，常量为 `48 * 1024`。这是 Kotlin 字符数上限，不是 UTF-8 字节上限；无 offset、续读游标或截断提示。较长工具调用还可能先占满关联消息的返回预算，使结果正文没有展示机会。

历史原文仍在存储中，但“原文保留”不等于“模型可以读到任意部分”。工具原始输出若在落库前已被外置，历史中保存的也是预览与产物路径，需要继续走文件回读。

建议：优先按 `message_id` 定位，明确消息正文与关联消息的边界，提供有界分页及 `next_offset/truncated`。先明确 offset 的单位与目标字段，再同步 schema、提示词和实现；不要先截成 48K 再分页。回读保留只读语义，不触发工具重放。

验收：超过 48K 的消息尾部放置唯一事实，能够连续分页找到；关联 ToolCall/ToolResult 均可定位；空页、非法范围和不存在 ID 明确返回；压缩后与重启后仍可读到同一事实。

### P1：统一 history_search 的 index 契约

**确认缺陷**：[ProviderClient.kt](../harness/src/main/java/top/tianyan/app/harness/ProviderClient.kt) 的工具描述说搜索 index 是活动分支的消息索引，可直接传入 `history_read`；[prompts/system/tools.md](../harness/src/main/assets/prompts/system/tools.md) 却说它只是搜索命中序号，不能直接传入。

[SessionTreeStore.kt](../harness/src/main/java/top/tianyan/app/harness/session/SessionTreeStore.kt) 的 `searchIndexed` 使用 `messages.withIndex()`，排序后保留原索引；读取按该分支消息索引解析。因此当前实现与 schema 一致，系统提示错误。应优先推荐稳定的 `message_id`，同时如实说明 index 在分支切换后可能变化。

验收：构造“搜索排序位置与消息索引不同”的命中，schema、提示词与实际回读结果一致。

### P1：补齐历史 write/edit 大参数的请求侧缩减

**确认缺口**：`truncateStaleToolResults` 只改 ToolResult；预算计算会计入 ToolCall.args，但常规缩减与字节兜底未提供历史参数的对应缩减阶段。摘要叙事序列化中的参数 240 字符限制仅用于摘要输入，不是主会话请求治理。

建议：在整段摘要判定前，对已完成、处于保护窗口之外的写文件内容和补丁正文做投影级缩减，保留工具名、路径、调用 ID、结果配对及原消息回读引用。只处理明确的正文参数，保留原 JSON 类型与结构；原始 transcript、执行参数、审批记录不变。不得把缩减后的历史调用作为可重放指令。

修改后重新估算预算；同步用量面板、摘要 replay 前缀和各协议映射。Responses 等协议可能携带原始轮次数据，需要验证缩减是否真正进入最终请求，避免只改中间模型。

验收：多轮大 write/edit 请求在触发摘要前明显减小；当前轮、未完成调用和最近保护窗口完整；原参数仍能分页回读；NATIVE、JSON_TEXT 与 Responses/Anthropic 映射保持合法调用配对。

### P1：建立“压缩后补回细节并完成任务”的链路回归

**评审范围内的测试缺口**：现有测试覆盖预算、引用提示、摘要 replay、增量压缩与并发恢复；所检查的测试未展示“摘要遗漏关键事实 → 主动历史检索 → 分页回读 → 继续执行 → 完成事件”的完整用例。这不等于宣称全仓库没有任何相近测试。

使用假 Provider / MockWebServer：将关键事实放在摘要省略区及消息尾部，脚本化模型请求检索与回读；验证真正找到原事实、工具动作只执行一次、审批等待与恢复成立，最终 Web 完成事件正确。另覆盖外置文件被清理、写入失败和机械摘要回退，不能仅断言提示文案包含 `history_read`。

### P2：逐步统一预算决策与来源诊断

这是架构改进建议，不是“天衍没有预算系统”。当前预算已覆盖 system、摘要、召回和工具预留，但不同阶段分别缩减，最终请求诊断缺少原因链。

建议先提取纯函数决策结果：输入来源、估算占用、保护原因、处理动作、回读引用及处理前后估算。继续使用现有 assembler/store 边界，先让诊断解释“为何缩减这条消息”，再考虑按价值分配不同上下文贡献的预算。分支投影已有来源信息，宜复用。

最终协议请求还包含 tools、system、召回与协议包装；消息列表内的 4 MiB 估算不能被描述为最终 JSON 的硬上限。可先记录最终序列化体积与估算差，再按实际协议设置发送前判定。不要无界重试。

### P2：明确引用生命周期与多模态恢复边界

工具产物会因 7 天保留期、64 MiB 工作区预算或 256 文件上限被清理。消息仍存活时，文件引用可能已失效；摘要不应将引用存在当作文件仍可读取的保证。

建议保留产物完整性和可用性信息，失效时明确反馈；评估活跃会话引用是否需要有限期保留。当前 `historyLabel` 的用户消息只返回文本，不能据此承诺被省略的图片可以用 history_read 恢复。借鉴上游媒体引用方式前，应先验证实际媒体存储与模型可用的读取接口。引用不是媒体内容，也不能支持未观察过的视觉结论。

## 建议落地顺序与不变量

1. 修正 index 文案，补历史分页和回读契约测试。
2. 增加历史大参数投影缩减，并验证最终三种 Provider 协议与两种工具调用模式。
3. 补充压缩后任务继续的假 Provider 链路回归。
4. 在已有诊断基础上增加原因与来源，再推进统一预算决策及引用生命周期。

所有实现应保持：原始历史不因请求投影而被覆盖；审批与权限来自现有门控；回读不重放副作用；产物沿当前执行环境访问，不能回退到手机同名文件；落盘与诊断沿用[既有脱敏边界](SECURITY_SURFACE.md)；不以压缩为由将低权威背景资料升级成权限或用户指令。
