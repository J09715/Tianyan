# 让步总账（Concessions Ledger）

> 借鉴 DS Bot 的「DSH 让步总账」实践：所有为宿主平台/既有架构做出的临时妥协逐条登记，
> 注明原因与偿还路径；不做静默妥协。每条在偿还后移除。维护者：全体协作者，新增让步须登记。

## 登记规则

- 一条让步一行：**编号 · 妥协内容 · 为什么让 · 偿还条件/路径 · 登记时间**。
- 偿还完成后删除条目，并在对应 PR/提交信息中引用编号。

## 账本

### C1 · Room 无 FTS 全文索引（trigram）
- **妥协**：被压缩历史的检索用 `payloadJson.contains`（LIKE 语义）而非 SQLite FTS5 trigram 索引。
- **为什么让**：Android 平台内置 SQLite 的 FTS5 编译选项随系统版本不一，trigram tokenizer 不可用；自带 SQLite so 又增大包体。
- **偿还路径**：等 Android 15+（SQLite ≥ 3.44）成为 minSdk 主流后迁移 FTS5；或自建 n-gram 表。
- **评估更新（2026-10-24，DSH 借鉴批次收口）**：中途评估过 FTS5 + unicode61（CJK 逐字 token + phrase 查询）方案，**结论 ROI 为负、维持让步**——① unicode61 下英文按词切分，子串匹配（"gradle" 命中 "subgradlex"）语义丢失，与 contains 不等价；② 需要运行时 FTS5 可用性探测 + 回退、外部内容表 + 触发器同步、blob 化条目不可索引等维护面；③ 检索仅发生在模型主动调用 history_search 的低频路径。触发条件改为：Android 15+ 普及使 trigram 真正可用，**且**检索成为实测瓶颈。
- **登记**：2026-10-24（DS Bot 借鉴批次；其 docs/plans/2026-10-09-context-and-memory.md 的 chat-index 设计为参照）。

### C2 · 群统一审批模式只在群维度会话上生效
- **妥协**：`GroupChat.unifiedApprovalMode` 覆盖成员的**群维度会话**（建会话与自愈时对齐）；成员私聊会话仍固定完全访问，Bot 的独立任务会话不跟随群模式。
- **为什么让**：审批 UI 面向群聊会话聚合（GroupApprovalDialog / OfficeWait），私聊与独立任务的审批入口尚未覆盖，贸然收敛会造成"等审批但无处审批"的死等。
- **偿还路径**：私聊会话接入审批面板后，把 `ensureSessionRow` 的私聊默认值改为跟随 Bot 级配置。
- **登记**：2026-10-24。

### C3 · list-bots 状态行不做未读消息统计
- **妥协**：状态行只含 会话运行态 + 活跃任务，不含"按发件人合并的未读数"（DS Bot 有）。
- **为什么让**：未读需要 seen-seq 按 (bot, sender) 双键聚合，现有 `GroupSeenEntity` 只存 (group, bot) 单键，扩键需迁移且消费场景少。
- **偿还路径**：出现真实需求时为 seen 表加 sender 维度并出聚合查询。
- **登记**：2026-10-24。

### C4 · Bot 能力白名单只覆盖 MCP 域
- **妥协**：`capabilityServerNames` 白名单只过滤 MCP 服务（挂载 + use_capability + legacy 直连）；宿主域（host）与插件域（plugin.*，含 bot-management）不受白名单约束。
- **为什么让**：host/plugin 是天衍自身能力面，按域禁用等价于改 SystemPromptBuilder 的工具面（牵动字节稳定契约）；DS Bot 的能力包同样不裁核心 harness。
- **偿还路径**：如需"无终端 Bot"这类产品语义，应做成 Bot 级 runMode/工具档位，而不是塞进 MCP 白名单。
- **登记**：2026-10-24。

### C5 · 接力暂停制保留迟到回报轮上限
- **妥协**：DS Bot 取消了全部数量硬上限（改为未读合并）；天衍保留 `MAX_LATE_ROUNDS = 10`（每群迟到回报自动开轮上限）。
- **为什么让**：天衍的迟到回报会自动为派单者开新轮（模型调用），无上限时后台任务风暴会直接烧 token；未读合并在 assignment 模型下没有直接对应物。
- **偿还路径**：若实测正常使用撞上限，改为按派单树聚合单轮回报。
- **登记**：2026-10-24（PR #72 审阅跟进）。

### C6 · 接力暂停态仅存内存
- **妥协**：`GroupRoundCoordinator.relayPausedGroups` 是进程内集合，进程重启后暂停丢失——已暂停群的后台迟到回报可能在重启后恢复自动开轮（仍受 `MAX_LATE_ROUNDS` 与用户消息重置约束，风暴有界）。
- **为什么让**：暂停是转发瞬态语义，持久化需要 group 表加状态列 + 恢复对账，收益场景（重启恰逢接力暂停且有迟到回报在途）罕见。
- **偿还路径**：把暂停标记并入 group_rounds 的终态或群配置表，恢复时对账一次。
- **登记**：2026-10-24（PR #72 审阅跟进）。

### C7 · 腐坏条目投影侧仍跳过（非 DSH 式整段拒绝）
- **妥协**：DSH 对读不懂的会话事件是 fail-closed 拒绝整段重建；天衍 `SessionTreeStore.decode` 对腐坏/超前版本条目**仍跳过投影**，但每次跳过都在 `SessionCorruptionLedger` 登记（error 日志 + 可查询记录）——"静默丢史"升级为"显式丢史"，丢史本身仍发生。
- **为什么让**：移动端单条坏数据拒绝整段会话加载会把用户锁死在会话外；`loadStrict`（恢复/审计路径）已保持严格抛错，宽松路径服务 UI 容错。
- **偿还路径**：开发者诊断面板暴露 `corruptionReport` + 提供条目级修复/导出工具后，可评估把主投影也改严格。
- **登记**：2026-10-24（DSH 借鉴批次，见 docs/DSH_BORROWING_ANALYSIS.md）。

### C8 · 审批审计不覆盖策略自动放行的每次调用
- **妥协**：`agent_approval_audits` 只登记 requests 表之外的三类事实（授权写入 / 豁免执行 / deferred）；FULL_ACCESS 或策略判定放行的每次调用不单独落审计（DSH 同样不记 per-call allow，只持久化 policy 变更）。
- **为什么让**：完全访问模式下逐调用落审计 = 每工具一行写入，量大而信息量低（会话模式本身已在 harness_sessions 持久化）。
- **偿还路径**：出现"放行追溯"真实需求时，把策略放行折叠为按 (会话, 模式) 的会话级审计行，而非逐调用。
- **登记**：2026-10-24（DSH 借鉴批次，见 docs/DSH_BORROWING_ANALYSIS.md）。

### C10 · 后台子代理批次进程被杀后不自动恢复
- **妥协**：`invoke_subagent background=true` 的后台批次在进程级 scope 运行，进程被杀后批次协程不自动重建（DSH 的 continuation manager 支持冷恢复）。
- **为什么让**：Lane 对话树已持久，重启后模型可用 task_id 续跑（continuable 的"数据保留"语义已达成）；自动恢复需要批次规格快照 + 启动对账 + 与 WorkflowRunManager 同级的 reconcile 体系，收益场景（重启恰逢后台批次在跑）罕见。
- **偿还路径**：对齐 WorkflowRunManager.reconcileInterruptedRuns 模式，把后台批次规格持久化并在启动时对账重放。
- **登记**：2026-10-24（DSH 借鉴批次，见 docs/DSH_BORROWING_ANALYSIS.md）。

### C9 · spill 保存失败时不保留全量内联
- **妥协**：DSH 的 spill 保存失败时"尽力保留原内联结果"（宁可上下文大也不丢信息）；天衍 `.tianyan-outputs/` 落盘失败时维持"截断 + grep/head/tail 分段提示"（有损，但预算可控）。
- **为什么让**：移动端上下文预算敏感，失败路径通常伴随存储异常（磁盘满/权限），此时全量内联一条超大结果可能直接顶爆请求字节预算，连锁触发 413/压缩，伤害面更大；截断文案已引导模型分段取回。
- **偿还路径**：若实测出现"落盘失败 + 截断导致模型反复重跑大命令"的行为模式，可在失败路径把截断阈值放宽一档（如 ×4）而非全量内联。
- **登记**：2026-10-24（DSH 借鉴批次，见 docs/DSH_BORROWING_ANALYSIS.md）。
