# ⚡ 天衍 (Tianyan) — 核心调用链路与执行时序 (Execution Traces)

文件/命令/MCP/PTY 的后端归属及会话关闭顺序见 [执行环境与资源作用域](EXECUTION_ENVIRONMENTS.md)。

主页面导航：首次引导完成 → 智枢聊天 → 左侧共享会话/功能抽屉 → 智枢、工坊、系统概览或设置。根页面顶栏通过 `LocalAppNavigation` 打开同一抽屉，各页面的 Navigation3 栈继续保留；选择会话或新建任务返回智枢根页面。非聊天根页面的系统返回键回到智枢，子页面正常弹出本栈。聊天欢迎示例仅预填输入，不发送消息或调用模型；顶部工作台控制默认收起，全部放行状态不随收起隐藏。手机聊天/浏览器及宽屏双栏继续共用既有布局，底部只计系统导航栏和 IME 的实际 Insets。

---

## 1. Agent 发起与工具执行循环 (Harness Loop)

```text
ChatScreen (UI)
  └─► ChatViewModel.send(prompt)
        └─► InteractiveSessionControl.send() → HarnessLoop.submit() → SessionInputController
              ├─► 会话锁内检查存在性/审批等待 → 创建任务 → 原子提交消息/operation/task RUNNING 或输入队列 → 启动/排队回执
              ├─► ProviderRepository 读取 BaseURL / Model / ApiKey
              ├─► ProviderClient ➔ ProviderModelResolver（档案/凭证/偏好）
              │                   ➔ ProviderTransport / LlmApiRegistry（按模型 API 分发）
              │                     ➔ ChatApi / ResponsesApi / AnthropicApi ➔ reasoning & text / tool_calls
              └─► 当模型返回 tool_calls:
                    ├─► HarnessApiMapper 映射 read / write / edit / base / process
                    ├─► ToolRoundDispatcher：连续只读调用限流并行；变更/编排工具形成前后顺序屏障
                    ├─► DurableToolRunner 等待执行意图持久化
                    ├─► ToolExecutor / ToolExecutionBoundary 等待前置检查点（可否决）
                    │     ├─► 既有 PLAN / ApprovalPolicyEngine / 宿主权限门控
                    │     └─► WorkspaceToolBackend（read/write/edit）或其他工具分派
                    │           └─► WorkspaceToolOperations / LinuxRuntime 等具体实现
                    ├─► 等待后置检查点；只追加脱敏说明，保留成功/审批状态
                    ├─► DurableToolRunner 等待 ToolResult 提交到对话历史 (core:database)
                    └─► 继续进入下一轮推理，直至任务全部完成
```

检查点由受信任应用代码注入，按注册顺序运行。前置回调异常会阻止执行；后置回调异常只形成说明，不能把已成功写入改为失败并诱发重试。审批等待与 Lane 的审批移交也保留原标识和状态；取消在每个等待屏障后继续传播。事件总线仅供观察，不承担这些控制屏障。文件写入仍先捕获轮前内容，成功后捕获实际后像；超限文件报告恢复证据不完整。

工具结果落库前发生进程中断或取消时，有副作用的悬空调用写入 `TOOL_OUTCOME_UNKNOWN`（`ToolResult.errorCode` 与模型可见说明），不能把缺少结果解释为执行失败或尚未执行；模型须先通过只读查询核验实际状态，无法核验时取得用户确认后才可重新发起。未声明安全重放的持久化工具意图同样按未知处理。已提交的成功结果保持原样，重复恢复不会再追加同一调用的未知结果。只读工具在进程死亡后可重放，用户停止时只写 `TOOL_INTERRUPTED`。

失败结果可附带持久化的 `ToolRecoveryAdvice`（`recoverable / retrySameArguments / nextActions`）；旧消息默认无此字段。普通执行经过检查点后按工具类别生成建议，恢复生成的未知结果也带只读核验建议。审批等待和子 Lane 移交保留原控制标志。建议仅在模型消息投影时追加 JSON，原 UI 文本保持原样，上下文 token/字节预算包含这部分；`recoverable` 只表示有建议的检查路线，不代表安全重试。当前建议均不允许原参数自动重试，不授予权限、不启动执行，也不改变 DurableToolRunner 或 ReplayPolicy。

主会话的持久化任务结束时，`OperationCoordinator.completeTask()` 固定当前 operation 和 Lane 叶子，读取最多 257 个已提交历史条目，由 `TaskDeliveryBuilder` 生成本轮交付记录。`HarnessRuntimeRepository.completeTaskOperation()` 在同一 Room 事务内核对任务归属、当前 operation 和预期叶子，插入 `TaskDelivery` 消息、将任务更新为 COMPLETED，并完成 operation/输入队列收尾。读取、解码、插入或状态比较失败都会阻止本次完成；事务内失败回滚报告和状态，不留下“已完成但报告未保存”的中间态。报告沿不可变历史分支保留，重启可恢复，回退到报告之前的分支不再显示它；不需要数据库 schema 迁移。

交付记录只引用本轮主 Lane 的工具调用、结果和助手结论消息，不复制参数、输出正文或用户目标。工具成功仅表示记录中的执行状态，不等于用户目标已验证；当前始终明确 `GOAL_NOT_VERIFIED`。审批恢复采用最终提交结果，缺少调用起点、窗口/条目超限、未知结果、待审批、工具失败及损坏的文件指纹都分别记录缺口。最多保留 96 条观察和 32 个不同文件版本引用（日志、APK、图片或下载文件）；按类型、路径、指纹去重，同路径不同内容保留不同版本，重复版本保留首个来源。扫描/指纹不完整也显式记录缺口，超过范围不宣称证据完整。子 Lane 和先前恢复 operation 的证据不会自动合并。Android 聊天提供可展开卡片，展示文件类型、观察来源、时间及 SHA-256；Web 转写提供未验证状态及同一结构化 report。普通无工具聊天不额外显示空卡片。`TaskDelivery` 不进入 provider 上下文或压缩提示，可用 `history_search(query="task_delivery")` / `history_read(message_id)` 显式回读。

文件版本链路：`base` 的受控工作区标准 APK 目录前后扫描、下载适配器的实际文件指纹、图片 `read` 的实际返回字节 → `ToolFileArtifacts` → ToolExecutor 校验及路径脱敏检查 → 随 ToolResult 持久化 → TaskDeliveryBuilder 引用。stdout 路径或模型自述不能生成引用；未变化 APK 明确标为已有，只有成功构建后新增/变化的实际版本触发 `ApkGenerated`。记录标识历史观察的字节，不归档二进制、不证明当前文件仍存在，也不构成目标、安装或运行验证。范围与容量见 [执行环境文档](EXECUTION_ENVIRONMENTS.md#工作区文件版本观察)。

### 运行内的失败记忆

`OperationSnapshot.failureMemory` 保存当前 operation 的有界失败事实。`OperationCoordinator.settle()` 在结算工具结果时，从持久化 ToolCall 获取参数，以规范化 JSON 的流式 UTF-8 SHA-256 生成指纹；只保存安全工具名、类型、来源调用/结果 ID、同参失败次数和失效时间，不复制参数、日志或模型自写的经验。该状态与 ToolResult、operation 和 Lane 在既有 `settleEffect()` 事务内一起提交；结果插入或来源读取失败不提交新记忆。结算及审批/挂起状态更新的短存储临界区串行化，避免并发结果覆盖记忆和历史叶子；模型请求和工具执行不在该锁内。

记忆最多保留 32 个参数指纹，每次请求投影最近 8 条，单块最多 6,000 字符；30 分钟失效，在后续结算或状态转换时清除过期项。待审批与审批移交不计为执行失败；同参成功清除普通失败，之前的“结果未知”仍保留核验提醒，不能从后续成功推断旧副作用已核验。provider 回合、审批等待和挂起保留该运行状态；重启后能读取旧状态，等待审批的同一 operation 恢复后继续使用。普通崩溃后的新输入会接管为新 operation；完成、接管和新任务不继承旧 operation 的记忆，父子 Lane 也互不继承。旧格式快照默认没有记忆，不需要数据库迁移。

主会话 `ApiContextAssembler` 和子任务 `SubagentLaneRunner` 将该块作为末尾合成 user 背景资料送入 NATIVE / JSON_TEXT 请求，明确用户未发送该消息、只供参考、不授予权限或要求自动重试。它不进入稳定 system 前缀，不写入聊天消息或压缩摘要；独立于压缩后的历史保留窗口，因此原始失败被折叠后仍能提醒模型。主会话纯聊天和禁用工具模式不注入；子任务收束轮仍可参考这些事实说明限制。投影计入 token/字节预算，读取时核对 Lane 和 operation 归属及指针；不从旧会话或全局工具库取经验。现有 ToolCallLoopDetector 继续负责当前执行中的死循环拦截，失败记忆只提供可失效的建议。

请求前的会话投影：`ApiContextAssembler` → `CompactionManager.project()` → `inspect()` → `SessionContextProjector`（固定本次 Lane 叶子；读取最新快照及增量窗口）→ 纯 Kotlin `SessionProjectionBuilder` → `CompactedContext`。主会话上下文用量面板、模型切换和压缩重读复用同一 `project()`。检查结果含消息/摘要/召回的来源、并发补回标记及损坏条目代码；之后的提示词注入、协议映射、图片及字节截断仍由请求组装层负责。

压缩流程：锁外生成摘要 → Lane 锁内重读并核对消息前缀 → 记录快照来源水位线 → 提交不可变压缩条目 → 重新投影已提交分支。重读后、提交前由其他事务追加的消息和分支摘要，会立即通过水位线补回；压缩返回值与下一次投影一致。损坏快照或旧格式保留消息解码失败时回退完整活动分支，并在检查结果中注明原因；存储读取异常仍向调用方传播。

Web 会话发送：认证 REST → `WebChatRunProtocol` 解析输入模式 → `WebChatRunTracker` 预留客户端关联 ID → gateway / `SessionControl.submit()` → 持久化接收回执。任务、完整用户消息及 operation 在启动事务中一起提交；受理后的队列投影刷新失败不改写成功回执。有独立任务 ID 时，经 `AgentTaskRepository.observeTask()` 观察精确任务状态；终态通过 `loadStrict()` 重读已提交消息，再报告 SSE 完成/失败，读取异常报告 error 并保留现有消息。每次请求分别观察，排队后继不会因前驱结束而完成。客户端 `TaskReceiptTracker` 记住回执之前的审批/终态事件，回执不再重新激活任务；记忆仅保留到请求结束。显式远端 ID 不切换前台，受理前存储失败返回通用 HTTP 500，取消传播。

---

### 同轮工具并发与发布顺序

主回合和子智能体共用 `ToolCallContract`，先拒绝未知工具名，再解析 JSON 对象及校验参数；未知名不会因 `HarnessApiMapper` 的历史映射回退而执行 `base`。内置名称大小写与兼容别名使用同一 schema；旧式 `mcp__` 调用由实时网关核验可用性。Lane 写租约检查使用与 `ToolExecutor` 相同的参数归一化视图，因此 `file_path` 别名及参数包装中的真实目标同样受约束；持久化与执行仍保留原始参数，避免二次解包或改写 MCP 的合法键。

调度按模型调用顺序划分连续分组：`read A / read B → write A → read A` 的前两项最多 4 个并行，写入等待读取及结果提交全部完成，最后一次读取等待写入结果提交。只读白名单为 `read / history_search / history_read / load_rule`；计划、记忆、草稿以及 MCP、宿主和未知能力均形成屏障。子智能体也形成屏障，但依靠自身写租约协调，不在父回合整批持有工作区锁。

`OrderedToolPublication` 分别按合法调用的模型顺序提交意图与结果；工具执行体和其前后检查点可以并发，检查点扩展必须支持并发调用。参数错误及未知工具仍在校验阶段即时发布，不属于这条排序链。提交异常取消整组和后续屏障；工具自身抛出取消异常也取消同组等待发布的调用，避免排序等待悬挂。审批暂停只允许已启动的只读调用收尾，其余调用不会启动。

这些顺序屏障只覆盖同一轮。`base` 保持回合内顺序，但主回合、子智能体和审批恢复均不持有跨会话工作区锁，避免一个会话的长下载挡住同工作区或未选工作区的其他会话。其余需要锁的变更及审批恢复仍共享按工作区分片的互斥锁。任意 Shell 命令不提供结构化路径写租约，并发命令及结构化写入可能操作同一文件；读取也不会获得工作区读锁，因此并发会话之间不提供读写快照隔离。结果排序等待计入并发许可，限制待提交结果数量；一个慢读取可能暂时占住整个读取组的许可。

资源调度与写入隔离分开：本地 LinuxRuntime.execute 在初始化后、目标进程分配前，经过 `ForegroundCommandAdmission`。已识别的重型前台命令跨会话/子任务/工作区共用一个名额，普通命令可继续执行；受信任工作台构建脚本明确提升资源需求，Harness 的 RTK 包装保留改写前分类。资源等待不消耗命令执行超时；取消等待不会启动目标命令。执行收尾失败的前台进程会阻止后续重型分配，成功清理后恢复；此规则不覆盖远端、后台、交互 PTY 或外部 MCP 的进程。

子智能体 Lane 保持逐项执行；需要锁的变更调用从意图提交到结果提交使用与主回合相同的工作区锁。审批恢复由 `ApprovalToolRunner` 重用冻结的 ToolCall ID，先提交不重复写消息的执行意图，再执行及提交结果；进程重启因此能识别“已开始重执行、结果未落盘”，不会被原来的审批等待结果掩盖。普通写入锁覆盖结果提交，子智能体编排不整批持锁，子工具逐项取锁。取消或提交异常时重读持久化结果：已提交终态保持原样，只有仍无终态的调用补上结果未知说明，不自动重执行。

Lane 的取消与异常收尾通过 `SubagentLaneFinalizer` 在不可取消区先补齐当前悬空工具结果，再清除运行指针。历史读取或补齐提交失败时保留指针，启动恢复仍可发现该运行；执行意图未落盘时不制造工具结果。声明可安全重放的只读调用记录中断，其他调用记录结果未知；主回合与 Lane 中执行体抛出的非只读异常同样不能证明副作用失败。异常返回与超时汇总将核验指引交给父智能体，即使后续无关失败覆盖“最近失败”也不会丢失未知结果的说明。

同一进程中的新输入接管也经过 `OperationCoordinator.settleInterruptedTool()`：`acceptRun`、`acceptQueuedRun` 和 `beginRun` 替换挂起运行前，先严格读取当前分支及 pending call 的已提交结果，再补齐未决调用。子 Lane 收尾共用该规则；已有终态保持原样，审批恢复的旧等待结果不算终态。读取、解码或补齐提交失败时，不结束旧 operation，也不提交新输入；排队输入保留供重试。本次接管不执行旧工具，未知副作用的核验指引先进入历史，再追加新的用户消息。

旧运行正常结束事务会先解除 `next_run` 输入与旧 operation 的关联，再清理该运行的 steer / follow_up 指令；后继输入保留原顺序、任务 ID 与图片。结束提交后、新受理前中断时，队列及 QUEUED 任务仍可重读并重试。直接输入与排队输入共用 `acceptRunTakeover`，把旧运行结束、可选队列消费、可选任务 RUNNING、完整输入及新 operation 合并为单个事务，并核对旧运行指针及历史叶子；队列缺失、类型或归属不符、任务缺失/归属错误/已取消、输入写入失败或并发运行/历史变更都会回滚接管，保留现有运行及悬空指针。子 Lane 使用相同受理路径，接管不会改动主 Lane。悬空工具结果的补齐先单独提交，因此受理失败也不会丢掉未知结果说明；重试保持幂等。运行结束/启动事件在接管提交成功后发布。

不追加输入的 `beginRun` 也使用同一接管事务，已有运行中或审批等待 operation 直接复用；挂起 operation 才会被替换。新运行创建或旧结束记录提交失败时，旧运行、队列及历史叶子一起保留；工具结果补齐不改变挂起状态，下一次 `beginRun` 仍可重试替换。无输入启动不追加消息，新的 `startLeafId` 与已有历史叶子一致；并发指针或叶子变化拒绝提交。

设计参考：[DeepSeek Harness 工具并发设计](https://github.com/deepseek-ai/deepseek-harness/blob/master/.agents/notes/implemented/feature/2026-07-10-parallel-tool-call-execution.md)。

---

## 2. 宿主与沙箱存储挂载 (Storage Mount)

```text
StorageMountSettingsScreen (UI)
  └─► SettingsDataStore (保存 mountDownloadEnabled / customMountBindings)
        └─► ProotCommandBuilder.build()
              └─► 动态追加 "-b /storage/emulated/0/Download:/sdcard/Download"
                    └─► PRoot 进程启动，沙箱内可直接访问 /sdcard
```

---

## 3. 终端交互与原生 PTY (Matrix Terminal)

```text
TerminalScreen (UI)
  └─► TerminalViewModel
        └─► TerminalPtyManager.createPty()
              └─► JNI pty.c (openpty / fork / execve proot)
                    ├─► PtyInputStream ➔ TerminalViewModel.screen (VT100 状态机解析)
                    └─► PtyOutputStream ◄─ TerminalScreen 键盘与辅助按键输入 (ExtraKeys)
```

---

## 4. 任务拆解与进度卡片 (Task Plan Checkpoints)

```text
Model Output (包含 - [ ] / - [x] 格式文本)
  └─► ChatScreen.kt -> extractTaskPlanSteps(message.text)
        └─► 当步骤数 >= 2 时 ➔ 渲染 TaskPlanCard
              ├─► 动态计算进度百分比与完成度徽章 (LinearProgressIndicator)
              ├─► 提供触觉反馈与平滑展开/折叠动效
```

---

## 5. 宽屏与折叠屏双栏联动 (Dual-Pane Layout)

```text
ChatScreen.kt (BoxWithConstraints)
  ├─► maxWidth >= 720.dp:
  │     ├─► 左栏 (48% 宽度): ChatPaneContent (Agent 对话与输入框)
  │     ├─► 中间: VerticalDivider
  │     └─► 右栏 (52% 宽度): TerminalScreen(project = workspace) (实时 Linux 终端)
  └─► maxWidth < 720.dp:
        └─► 单栏 Phone 视图
```

---

## 6. Agent 前台命令与托管进程 (Command Lifecycle)

```text
AgentSettingsScreen
  └─► SettingsDataStore.baseCommandTimeoutSeconds（1–60 分钟，默认 10 分钟）
        └─► ToolExecutor.executeBase()
              ├─► 未传 timeout_seconds：读取用户默认值
              └─► 单次覆盖：校验 1–3600 秒 ➔ LinuxRuntime.execute(ShellCommand)

Harness process(action=start, id, command)
  └─► ToolExecutor.executeProcess()
        └─► LinuxRuntime.startBackground(type=COMMAND)
              └─► ProcessRegistry
                    ├─► 保存 agent-process:<id> / PID / LinuxSession
                    ├─► 缓存并暴露日志
                    └─► status / logs / list / stop
```

> **进程生命周期特别说明**：
> PRoot 启动参数包含 `--kill-on-exit`。普通 `base` 中的 `nohup`、`setsid`、`&` 或自行 daemonize 不能保证跨 PRoot 会话存活；必须使用 `process` 托管，并让被托管命令保持前台运行。Android 强制停止、系统回收应用进程或设备重启不属于进程托管保证范围。

---

## 7. 工作区导入、导出与构建 (Workspace Lifecycle)

```text
WorkspaceScreen
  └─► WorkspaceViewModel
        ├─► WorkspaceManager.createProject()
        ├─► importProjectArchive() ➔ SAF URI ➔ 安全解压到 /workspace
        ├─► importGithubProject() ➔ LinuxRuntime.execute(git clone)
        ├─► exportProject() ➔ ZIP ➔ SAF 目标目录
        └─► WorkspaceBuildTaskCoordinator.start(project)
              └─► WorkspaceBuildRunner.runProject()
                    ├─► BuildEnvironmentPreflight
                    ├─► Android / Flutter：最长 30 分钟构建
                    ├─► 持续 StateFlow、通知与构建日志
                    └─► ApkArtifactVerifier ➔ APK 安装入口
```

---

## 8. ARM64 Android 离线套件安装与构建 (Sandbox Android Toolchain)

```text
WorkspaceScreen / ToolCenterScreen
  └─► ToolManager.batchInstallComponents() / GenericRecipeInstaller
        ├─► LocalPluginPayloadManager 流式复制 payload，并按字节上报 [COPY] 进度
        └─► android-suite-offline manifest + install-android-suite.sh
              ├─► [TIANYAN_PROGRESS:n] 协议 ➔ InstallEvent.Progress
              ├─► 安装不可变 JDK / SDK / AAPT2 / NDK / Gradle / CMake / Ninja / Flutter
              ├─► /root/.gradle/init.d/tianyan-android-ndk.gradle 唯一注入 android.ndkPath
              └─► gradle.properties 固定移动端资源策略
                    ├─► daemon=false / parallel=false / workers.max=2
                    └─► Gradle Xmx=1024m / Metaspace=384m / SerialGC

WorkspaceBuildRunner
  └─► build_android.sh / build_flutter.sh
        ├─► 清理 local.properties 中遗留 ndk.dir，不再写回
                    └─► --no-daemon --max-workers=2 ➔ 构建与产物校验
```

---

## 9. 无线 ADB 自动发现与日志抓取 (Wireless ADB / Logcat)

```text
DeveloperScreen
  └─► EmbeddedAdbManager
        ├─► NsdManager/mDNS 发现 _adb-tls-pairing._tcp 与 _adb-tls-connect._tcp
        ├─► 首次输入 6 位配对码 ➔ TLS + SPAKE2+ 配对 ➔ 私钥写入应用私有目录
        ├─► 后续启动发现连接端口 ➔ 自动复用持久密钥重连
        └─► shell UID 执行 logcat（包名 PID / Tag / 优先级 / 关键词过滤）

Harness host(action=logcat)
  ├─► 优先 EmbeddedAdbManager（无需 Shizuku/Root）
  └─► 无线 ADB 不可用时回退 PrivilegeManager

PRoot 沙箱 /opt/tianyan/bin/logcat-grabber & tianyan-host logcat
  └─► HostBridge (127.0.0.1:7980)
        ├─► POST /api/logcat ➔ EmbeddedAdbManager.captureLogcat()
        ├─► POST /api/shell ➔ 内置无线 ADB 回退（无 Shizuku 亦可执行 Shell）
        └─► POST /api/install-apk ➔ 内置无线 ADB 静默安装（连通时免系统弹窗）
```
