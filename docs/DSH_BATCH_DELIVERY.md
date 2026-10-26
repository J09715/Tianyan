# DSH 对标批次交付清单（feature/dsh，2026-10-24）

> 本文件是 `feature/dsh` 分支上 DSH（deepseek-ai/deepseek-harness）对标工作的**交付索引**，
> 供 review 与提交拆分使用。分析与决策记录见 [DSH_BORROWING_ANALYSIS.md](DSH_BORROWING_ANALYSIS.md)，
> 让步见 [CONCESSIONS_LEDGER.md](CONCESSIONS_LEDGER.md)（C7/C8/C9 + C1 评估更新）。
> 最终认证：`assembleDebug` 全量构建成功；harness 全套 1895+ 测试、core:database、app DI、
> runtime webchat 契约、`architectureCheck` 全绿。

## 建议的提交拆分（三段，各自可独立 review）

### 段一：P0 持久化完整性（feat, DB v80→81）

- `core/database`：`HarnessEntryEntity.payloadVersion` 列 + `AgentApprovalAuditEntity` 表 +
  `MIGRATION_80_81` + schema 81.json
- `harness`：`SessionEntryPayload.kt`（版本常量/相邻迁移链/腐坏台账）+
  `SessionTreeStore` 解码 fail-closed 改造 + `ApprovalAuditRecorder` + 三处埋点 +
  `PlanBlockedResult.kt`（棘轮腾位）
- 测试：`SessionEntryPayloadTest`、`ApprovalAuditTrailTest`、`ApprovalAuditMigration80To81Test`
- 文档：CONCESSIONS_LEDGER C7/C8

### 段二：P1 轮改动卡片 + spill 增强（feat）

- `harness`：`TurnChangesEvent` 消息 + `TurnChangeCards.kt` + `CheckpointStore.beginTurn`
  返回被关闭轮 / `activeTurnNumber` / `activeTurnFiles` + `FileCheckpointPersistence` 抽出 +
  六处穷举 when 补分支 + `ToolExecutor` spill hint KB 数
- `feature/chat`：`TurnChangesCard.kt` + 主列表/工作台接线 + strings
- `app`：Web 网关 `turn_changes` 渲染分支
- 测试：`TurnChangeCardsTest`

### 段三：P2 双分类器 + 消息级反馈（feat, DB v81→82）

- `harness`：`ExecutionEnvironmentClassifier.kt`（A 类执行器异常签名 / C 类 PRoot 拒绝方言）
  + `LinuxCommandToolBackend` base/process 归因接线
- `core/database`：`AgentMessageFeedbackEntity` + `MessageFeedbackService`（目标校验）+
  `MIGRATION_81_82` + schema 82.json
- `runtime`：`WebChatAgentGateway` 反馈接口 + 桥服务器 `/feedback` 路由 + `WebBridgeNotification.kt` 抽出
- `app`：网关实现反馈方法；`feature/chat`：`MessageFeedbackAction.kt` 自包含 UI
- 测试：`ExecutionEnvironmentClassifierTest`、`MessageFeedbackServiceTest`、`MessageFeedbackMigration81To82Test`
- 文档：CONCESSIONS_LEDGER C9 + C1 评估更新

### 段四：P2 后台子代理（feat，2026-10-25）

- `harness`：`BackgroundSubagentRegistry`（进程级 scope，批次与主轮解耦）+
  `SubagentOrchestrator` background=true 从拒绝改启动（收据/完成投递）+
  `HarnessLoop.deleteSession` 取消后台批次
- `app`：`AgentForegroundService` idle-stop 与电租约感知 `hasActive`
- 测试：`BackgroundSubagentRegistryTest`（4 用例，真实 PromptQueueManager + Room）
- 文档：CONCESSIONS_LEDGER C10（进程被杀批次不自动恢复）；未提交，待授权

### 段五：tools 预存测试回归修复（fix, 2026-10-25）

- `LocalPluginStore.writeSelection` 自愈校验：Windows 上 AtomicFile finishWrite 的
  rename 覆盖失败导致插件版本指针停留在旧值（8 个测试预存失败）；修复后全绿。
  根因与修复记录见 KNOWN_ISSUES.md。
- `core/datastore`：`RequiresAtomicRenameOverwrite` 守卫 Rule——DataStore 的 8 个
  环境性失败（库内 rename，Windows）转为显式跳过，本机测试全绿，CI 覆盖不变。

### 通用（随段一或单列 chore）

- CI：`.github/workflows/architecture-baseline.yml` harness-contracts 白名单补录 6 个测试类
- 文档：`DSH_BORROWING_ANALYSIS.md`（分析+决策）、`DSH_BATCH_DELIVERY.md`（本文件）

## 核查闭环项（无代码，决策记录在 ANALYSIS 文档）

- 技能目录 digest 注入：不做（prefix cache 字节稳定契约已覆盖）
- 压缩三事件括住：不适用（append-only 单 entry 原子落库无半完成态）
- 溢出重试 generation 检查：已等价存在（folded>0 + 单次标志）
- 定时投递 flush 确认：已覆盖且更保守（UNKNOWN 不自动重试）
- RoundBudget 续跑竞态：不存在（单协程局部计数器）
- 跨会话 FTS：ROI 为负不做（C1 偿还条件已更新）

## 本批次的普适工程经验（已入长期记忆）

1. Koin 新 DAO 双处登记（AppModule provide 函数 + app KoinModule single/import）——本批踩两次
2. 零余量 UI 文件加功能优先"自包含组件 + koinInject"而非参数钻孔
3. 修改既有提示文案时保持既有锚点字面连续（测试断言依赖）
4. sealed 消息测试编码必须用基类序列化器（判别字段）
5. `AssertionError` 非 `Exception`，strict 断言用具体异常类型 + fail()
