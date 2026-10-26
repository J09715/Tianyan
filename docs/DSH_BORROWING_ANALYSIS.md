# DeepSeek Harness（DSH）对标借鉴分析

> 对标仓库：https://github.com/deepseek-ai/deepseek-harness（`dsh`，DeepSeek 官方开源 agent harness，TypeScript/Cordis everything-is-a-plugin 架构，约 55 包）。
> 参照克隆：`%TEMP%\deepseek-harness-ref`（2026-10-24，中文文档齐全：`docs/*.zh.md`）。
> 关系澄清：此前 PR #72 借鉴的 Fei2411/DeepSeek-Bot 只是 DSH 之上的多 Bot 团队插件；本文分析的是上游本体。
> 登记：2026-10-24，分支 `feature/dsh`。

## 总体判断

DSH 与天衍 harness 形态高度同构（消息树 / 审批链 / 压缩 / 子代理 / 工作流 / 定时全能对上），
它的价值不在"搬功能"，而在**边界语义的工程化细节**——恰好补天衍几处已知让步与静默风险。
另有一类**明确不借鉴**：Cordis 运行时插件树 / HMR / profile-bundle 分发是桌面服务端形态，
与 Android 编译期装配冲突；值得拿走的只是 seam 三角色（Definition / Provider / Consumer）的分类学，
用于整理 `HarnessPlugin` 六类贡献的文档命名。

## 借鉴清单（按优先级）

### P0（补硬伤）—— 本批次已落地

| # | 借鉴点 | DSH 机制 | 天衍落点 |
|---|---|---|---|
| 1 | 会话条目 payload 格式版本 + 解码 fail-closed | Session 是 append-only 事件日志；未知事件默认 required（读到不认识的事件拒绝重建）；格式 N→N+1 相邻迁移链 + 后继 generation 排他发布 | `harness_entries.payloadVersion` 列（v81 回填 1）；`SessionPayloadMigrations` 相邻链；`SessionCorruptionLedger` 腐坏台账；见让步 C7 |
| 2 | 审批审计对持久化 + unavailable fail-closed | `approval/asked`/`decided` 审计对写会话日志；缺应答者/异常/非法值归一为 unavailable = 拒绝 | 核查结论：asked/用户决定/过期/执行已由 `agent_approval_requests` 持久化且 TTL 过期 fail-closed ✓；真正的缺口是**会话授权（grants）豁免执行与授权写入无痕** → 新表 `agent_approval_audits`（grant_written / grant_exempted / deferred 三类）+ `ApprovalAuditRecorder`；缺审批仓库 → 失败结果不放行（回归测试钉死）；见让步 C8 |

### P1（已有机制对齐增强）

3. **技能目录 digest 替换注入**——**核查后不做（2026-10-24）**：天衍技能目录在 system prompt 内，
   但已有 prefix cache 字节稳定契约（逐轮不变、技能按全会话累计，见 ApiContextAssembler 头注）；
   DSH 方案解决的是"目录放会话历史 + 技能集变化只追加替换消息"，天衍当前架构下技能集变化是
   低频事件且一次重建可接受，迁移要动契约核心。若未来技能规模上到百级再评估。
4. **压缩三事件括住 + 溢出重试的 generation 检查**——**核查后无需改动（2026-10-24）**：
   - 三事件括住（start 锁/summary/end）对天衍架构不适用：DSH 需要括号是因为它用
     surface replace 两阶段替换（存在"替换一半"中间态，崩溃遗留锁需可检测）；
     天衍压缩是 **append-only 单 entry 事务落库**（CompactionManager.compact：锁外摘要、
     锁内重读-对账-原子追加），崩溃语义天然是"没有压缩"（下次组装重新触发），
     不存在半完成态。强加括号反而引入需要清理的遗留锁状态。
   - generation 检查已等价存在：`recoverFromContextOverflow` 的 `folded <= 0 → null`
     （状态未前进不返回）+ 调用方 `overflowRecovered` 单次标志（HarnessProviderRunner:287/345，
     每轮至多一次紧急压缩，无效重试不烧钱）+ `ProviderOutputRecovery` 同样 `!= null` 才采纳。
     DSH 的"surface replacement generation 前进才重试"在天衍对应"折叠数 > 0 才重试"。
4a. ~~**轮改动交付物卡片**~~ —— **已落地（2026-10-24，见下）**。
5. **spill 定位符 + retrievalHint**——**主体已存在（opencode 批次），2026-10-24 补齐**：
   两处 spill 消费方（ToolExecutor 截断文案 / SubagentSummaryPagination）均已有
   "定位符 + read offset/limit 分页提示 + 总字符数"；本轮补落盘 KB 数随 hint 给出
   （模型据此决定分页步长）。"保存失败保留原内联"有意不抄：移动端预算敏感，
   失败路径维持"截断 + grep/head/tail 分段提示"（比全量内联更可控），见让步 C9。
6. **定时投递 flush 确认**——**核查后无需改动（2026-10-24）**：`BotScheduleRunner` 的
   stage→claim→finish 三段 + `recoverInterruptedDeliveries` 启动恢复 + 未知结果标
   UNKNOWN 不自动重试，已覆盖 DSH "flush 确认后写回执 / 错过只补最新" 语义，且在
   防重复投递上更保守（DSH 显式让步"可能重复投递"，天衍选择"可能丢拍标 UNKNOWN
   人工核对"——移动端宁可丢拍不重复打扰，与既有 memory 记录的让步一致）。
7. **"沙箱拦住了 vs 沙箱坏了"双分类器**——**已落地（2026-10-24，P2 批次提前）**：
   `harness/ExecutionEnvironmentClassifier.kt` 两类正交分类——A 类执行器异常
   （cause 链签名：运行时未就绪 / QEMU 未就绪 / PRoot 无法启动）、C 类 PRoot 拒绝方言
   （stderr 的 `proot ` 前缀守门，普通命令错误不误判）。`LinuxCommandToolBackend`
   base/process 两路径接线：命中环境分类返回【环境故障】专用文案（明确"与命令内容
   无关、不要重试、上报用户"），未命中保持原语义。测试 9 例 + CI 白名单补录。
8. **续跑无竞态调度**——**核查后无需改动（2026-10-24）**：`RoundBudget` 是
   `runLoopInternal` 内的单协程局部计数器（HarnessLoop:1198），advance/canContinue/
   beginNextSegment 无并发窗口；续跑提示先 enqueue 落盘再消费，进程被杀可恢复；
   审批恢复经 sessionJobs join 串行。DSH 的三重防护（预留 Round 号 / pre-step 双重
   校验 / flush 检查点）面向跨 agent 调度器与 inbox 认领的并发，天衍单会话顺序流
   不存在该竞态面。

### P1 已落地：轮文件改动卡片（2026-10-24）

DSH 的 deliverables 用轮始/末工作树快照 diff 自动得出"本轮改动文件"；天衍 `CheckpointStore`
本就为 rewind 捕获每轮写工具触碰文件的「轮初内容 + 最后写入后凭据」，直接复用为数据源
（比 git diff 更精确覆盖无 git 的工作区）。

- 消息：`HarnessMessage` 新子类 `TurnChangesEvent`（UI-only：持久化进会话树、
  `ApiMessageProjector` skip 列表排除出模型上下文；created/modified/touched 三类路径清单）。
- 发布：`harness/checkpoint/TurnChangeCards.kt`——`classify`（轮初 null+凭据→created；
  内容变化→modified；缺凭据→touched；写相同内容→忽略）；先 `store.append` 落库再
  `publishPersisted` 上屏。
- 时机（两个互补挂点）：轮 Completed 时从活动轮快照即时发布；下一轮用户输入
  `beginTurn` 关闭上一轮时补发（覆盖停止/失败/审批过期轮）。补发前按轮号查历史判重，
  一轮至多一张卡片。
- 存储：`CheckpointStore.beginTurn` 改返回被关闭的上一轮 `Checkpoint?`；新增
  `activeTurnNumber` / `activeTurnFiles`；`FileCheckpointPersistence` 抽出独立文件
  （CheckpointStore 259 行，同时给棘轮腾位）。
- UI：`TurnChangesCard.kt`（feature/chat 新文件，沿用 ModelSwitchCard 分隔线卡片语言，
  File 图标 + 分类计数标题 + 路径清单）；ChatMessageList/WorkbenchPanels/Web 网关接线；
  中英 strings。
- 测试：`TurnChangeCardsTest`（分类边界/落库上屏/判重/活动轮发布/beginTurn 返回值），
  CI harness-contracts 白名单已补录。
- 已知边界（如实）：卡片只反映 write/edit 写工具可见的改动，shell 命令创建/删除的文件
  不经 capture 不入卡片；活动轮未关时进程被杀，该轮卡片丢失（与 rewind 持久化同一窗口）。

### P2（新能力方向，按需立项）

- **可继续（continuable）子代理**——**后台骨架已落地（2026-10-25）**：
  `BackgroundSubagentRegistry`（进程级 scope，批次与主轮解耦不锁父会话）+
  `invoke_subagent background=true` 从拒绝改为启动（立即返回 backgroundStartedText
  收据，完成后 backgroundCompletionText 以 STEER 消息入队父会话——运行中被下一步前
  消费、空闲时等用户输入触发推理）；FGS idle-stop 与电租约判定感知
  `hasActive`（AgentForegroundService），会话删除取消其全部后台批次。
  续跑能力沿用既有 task_id Lane 续跑（数据保留即 continuable 的 seed 语义）。
  **让步 C10**：进程被杀后批次不自动恢复（Lane 持久可续跑，调度身份恢复让步）；
  后台 Lane 审批沿用 deferred 语义（INCOMPLETE 汇总报告）。
- **Agent Teams 任务板**——**评估后不做（2026-10-25）**：DSH 自身也标 experimental。
  其任务板针对的三个问题在天衍各有已落地的对应物：并发写冲突 → 子代理 write_paths
  写租约波次（buildWriteCleanWaves）；任务状态机 → AgentTaskStateMachine + 后台审批
  通知；多 Bot 协作 → 群提案治理 + 接力派单树。缺实证需求（尚无"多 Bot 需要跨会话
  看彼此任务依赖"的真实用例），硬造任务板 = 为对标而对标。触发条件：出现跨会话
  协作任务依赖的真实场景（如多 Bot 联合构建一条流水线）再立项。
- **跨会话全文检索**：SQLite FTS，跨会话按最强命中分组——正对让步 C1。
- ~~轮改动交付物卡片~~ —— **已提前落地（P1，2026-10-24）**。
- ~~"沙箱拦住了 vs 沙箱坏了"双分类器~~ —— **已提前落地（P1-7 顺延，2026-10-24，见上）**。
- **消息级反馈事件**——**API 层已落地（2026-10-24，Web 先行）**：
  `agent_message_feedback` 表（DB v82，messageId 唯一 + REPLACE upsert = last-write-wins；
  DSH 的 ifVersion 乐观并发面向多客户端同写，天衍单设备单写者不做版本字段）+
  `MessageFeedbackService`（写入门面：目标校验"活动分支上的非空 assistant 消息"、
  rating 枚举、备注 trim）。Web 桥暴露 `/conversations/{id}/feedback` GET/POST
  （WebChatAgentGateway 接口 +2 方法）；HarnessLoop 会话删除链清理反馈。
  **App 内 UI 已闭环（2026-10-24，同日第二批）**：`MessageFeedbackAction.kt` 自包含
  组件（koinInject 直取 Service + CurrentSessionTracker，**零参数钻孔**），挂在
  AssistantBubble 操作行（重新生成/分支/复制同排的入口图标），点开 👍/👎 FilterChip
  + 可选备注对话框，提交 Toast 反馈、失败降级提示；ChatMessageBubbles 净 -3 行
  （TaskPlanCard 腾位）。

## 本批次实现记录（P0）

### 会话条目版本化 + fail-closed（P0-1）

- `HarnessEntryEntity.payloadVersion: Int`（`@ColumnInfo(defaultValue = "1")`）；
  `MIGRATION_80_81` 建列回填 1 + 建审计表。
- `harness/session/SessionEntryPayload.kt`（新）：
  - `HarnessEntryPayloadVersion.CURRENT = 1`（当前无历史版本，首次改格式时加 Step 并 bump）；
  - `PayloadMigrationStep`（强制相邻 N→N+1，纯函数）+ `SessionPayloadMigrations.upgrade`
    （链上缺边抛 `NoSuchElementException`）；
  - `SessionCorruptionLedger`：每会话登记被跳过的腐坏条目（id/类型/版本/原因），容量封顶，
    纯内存诊断。
- `SessionTreeStore`：写入恒盖 `CURRENT`；`decode()` 对 超前版本 / 迁移失败 / 解码失败 三种腐坏
  登记 ledger + error 日志（原先 runCatching warn 后静默丢）；`loadStrict()` 保持严格并版本感知
  （超前版本显式报错）；新增 `corruptionReport(sessionId)` 诊断入口。
- 其他直接构造 `HarnessEntryEntity` 的写入方（CompactionManager/BranchSummarizer/OperationCoordinator）
  依赖默认参数 =1，行为不变。

### 审批审计台账（P0-2）

- `core/database/AgentApprovalAuditEntity.kt`（新）：`agent_approval_audits` 表 + Dao + Repository。
  只登记 requests 表覆盖不到的三类事实（grant_written / grant_exempted / deferred），
  asked/批准/拒绝/过期/执行不重复登记。
- `harness/approval/ApprovalAuditRecorder.kt`（新）：埋点收口；审计插入失败降级 warn，
  绝不阻塞主链路（审计是旁路不是门禁）；`deleteForSession` 随会话清理。
- 埋点三处：`ToolExecutor` 豁免执行（grantExempted）与 deferred 交接（deferred）；
  `HarnessLoop.resolveApproval` 授权写入（grantWritten，紧邻 grants.grant）；
  `HarnessLoop` 会话删除时清理审计。
- Koin：`coreDatabaseModule` 提供 Repository；harness `KoinModule` 提供 Recorder；
  `ToolBackendModule`/`KoinModule` 注入 ToolExecutor 与 HarnessLoop（均可空默认，测试零改动）。
- 棘轮对齐：PLAN 拦截结果构造抽至 `harness/approval/PlanBlockedResult.kt`，
  ToolExecutor 399/400、HarnessLoop 1679/1680、KoinModule 554/555 均达标。

### 验证

- `ApprovalAuditMigration80To81Test`（v80 建旧行 → v81 校验回填 + 审计表可用）。
- `SessionEntryPayloadTest`（迁移链顺序/缺口/非法步骤；台账容量与清理；
  腐坏跳过 + 台账登记；超前版本按腐坏处理；loadStrict 严格抛错；append 盖版本戳）。
- `ApprovalAuditTrailTest`（豁免执行留痕；deferred 留痕；缺审批仓库 fail-closed 不执行；
  授权写入留痕 + 随会话清理；审计写入失败不阻塞工具调用）。
- 既有审批/压缩/投影测试回归。

## 参考（DSH 原始材料）

- `docs/architecture.zh.md`（总览）/ `docs/subsystems/*.zh.md`（逐子系统权威文档）
- `docs/agent-lifecycle.zh.md`（turn/step/取消时序）/ `docs/tool-execution-pipeline.zh.md`
- `SAFETY.md`（边界声明措辞克制，SECURITY_SURFACE 对齐风格用）
