# 天衍（Tianyan）深度审计 · 汇总行动报告

> 汇总自五份维度审计：[architecture.md](architecture.md) · [ui.md](ui.md) · [security.md](security.md) · [perf.md](perf.md) · [quality.md](quality.md)
> 口径：只收五份报告里已有证据的条目，行号沿用原报告（未重新取证）。**本报告不改代码，任何数字都可在分报告溯源。**

## 0. 总览

| 维度 | 报告 | 条数 | 其中 P0 | 最高优先主题 |
|---|---|---|---|---|
| 架构/接线 | architecture.md | 16 | 3 | 功能实现完整但零调用 |
| UI/排版 | ui.md | 20 | 2 | 触控尺寸、壁纸模式容器 |
| 安全 | security.md | 14 | 2 | 签名私钥交付、WebChat 桥爆破 |
| 性能/内存 | perf.md | 20 | 5 | 流式热路径、非懒加载预览 |
| 质量/健壮性 | quality.md | 20 | 4 | CI 覆盖率、默认弱口令 |
| **合计** | — | **90** | **16** | — |

**合并与重排结果**：上游 16 条 P0 中，`perf#1` 吸收 `ui P1-5`（同一行两报告各报一次，机理相同：`AssistantText.text` 是 getter → `ChunkedText.toString()` 全量物化）；`arch` 的「AnsiTerminalBuffer 571 行死码」按三轴（收益中/风险低/改动大）降为 P2。**本轮 P0 共 15 条**，P1 归并后 14 条，P2 按主题归并 5 组。

**跨维度交叉验证结论**（安全 × 架构）：
- 未发现「安全开关被实现但未接线」型漏洞——安全工作点全部落在已接线路径上（WebChat 桥、HostBridge、ShowerBinderReceiver 均为活代码）。
- 也未发现「架构未接线导致安全能力失效」；唯一接近的是 `arch` 的「浏览器 4 项设置只读不可写」，其中 `allowRemoteConnect` 有写侧，**其余 4 项（homeUrl / desktopUserAgent / maxCaptureBytes / defaultFamily）不可配置**，安全上只影响可用性、不构成风险。
- 两处同源确认（无冲突）：`AnsiTerminalBuffer` = arch「零调用」= perf#20「snapshotLocked 全量重建但当前零生产调用」；`ChatMessageList` 流式项 = ui P1-5 = perf#1/#2。

---

## 1. P0 立即修（15 条）

**P0-01｜发行签名私钥与口令随工作树交付**（安全#1）
证据：`keystore.properties:1-4` 明文 `storePassword=tianyan2026`；`tianyan-local.keystore` 同目录；`apksigner` 证书 SHA-256 `29ba3b63…97` 与该 keystore 指纹一致（确为签发密钥）。
影响：拿到工作树副本即可签更高 `versionCode` 静默覆盖官方包，发行链失守。改法：交付物删两文件、口令改 `TIANYAN_RELEASE_*` 环境变量（`app/build.gradle.kts:42-53` 已支持）、该密钥签过的对外包按泄露流程轮换。
文件：`keystore.properties`(删) · `tianyan-local.keystore`(删) · `app/build.gradle.kts` · CI 拦截规则

**P0-02｜WebChat 局域网桥 6 位 PIN 无速率限制 + CORS `*` → Agent/终端接管**（安全#2）
证据：`WebChatBridgeServer.kt:110` 通配绑定；`:595` PIN 空间 10^6；`:225` 明文比较；`:538-543` 接受 query token；`WebChatHttp.kt:56-60` 连 401 也加 `Access-Control-Allow-Origin: *`；全目录无 throttle/lockout。
影响：同 Wi-Fi（或任意网页）并发枚举 → 工作区读写 + `/terminal*` PTY + 对话 API，默认 `FULL_ACCESS`（`ToolExecutor.kt:162`）宿主 exec 免审。改法：PIN 限速锁定并加长、CORS 白名单、token 只走 Authorization、默认绑 loopback、桥接会话强制 `ASSISTED`。
文件：`WebChatBridgeServer.kt` · `WebChatHttp.kt` · `WebTerminalBridge.kt` · `ToolExecutor.kt`

**P0-03｜cc-switch 缺配置时静默回落 `admin/admin123`**（质量 P0-3）
证据：`CcSwitchSandbox.kt:24-47` 文件缺失/读取失败/超时三条路径都 `printf` 同一对固定口令；`:44` `catch { Triple("admin","admin123","") }`；`:49-52` 三处 `ifBlank` 兜底，无日志无提示。
影响：本机/局域网可达该 Web 面即用公开默认口令登录。改法：`SecureRandom` 生成并落盘，失败抛「中枢未就绪」，至少 `Log.w` + 设置页红字。
文件：`feature/settings/.../CcSwitchSandbox.kt` · `CcSwitchViewModel.kt`（提示位）

**P0-04｜流式每 token 两次全量物化正文**（性能#1 + UI P1-5 合并）
证据：`ChatMessageList.kt:139` `last.text.length`、`ChatTypingIndicator.kt:31` `message.text.isNotBlank()`；`text` 是 getter（`HarnessMessage.kt:194` → `ChunkedText.kt:62` `toString()`），而 `ChunkedText.length`/`hasText` 本为 O(1)。
影响：30 KB 回复 ≈ 900 KB/s、100 KB ≈ 3 MB/s 纯垃圾，且 key 变化使投影必失效。改法：`:139` → `last.textContent.length`，`:31` → `message.textContent.hasText`（两处、零行为变化）。
文件：`ChatMessageList.kt` · `ChatTypingIndicator.kt`

**P0-05｜每 token 全量重投影消息列表**（性能#2）
证据：`ChatMessageList.kt:126` `rememberChatRenderItems(messages, …)` 以整个 `messages` 为 key，每次走 `ChatTimelineProjection.kt:38/45/62` 三趟全量 buildList。
影响：30 token/s × 600 item ≈ 1.8 万次分配/s，LazyColumn key diff 全表重跑——「越输出越卡」主因。改法：缓存不可变前缀，仅重投影末条；或流式文本改走独立 `State<ChunkedText>`。
文件：`ChatMessageList.kt` · `ChatTimelineProjection.kt`

**P0-06｜代码预览把行号栏渲染成 N 个 Text 且全在 verticalScroll 里**（性能#3）
证据：`ArtifactPreviewSheet.kt:306-346`（`:326-336` `lines.indices.forEach { Text("${idx+1}") }`）在非懒容器内一次组合；正文单 `AnnotatedString` 上限 500 KB（`SyntaxHighlighter.kt:256`）。
影响：1 万行文件 → 1 万节点同帧 measure/layout，数百 ms~秒级冻结（ANR 级）。改法：行号与正文合并 `LazyColumn`（一条 item = 一行），超 5 千行只渲可视区。
文件：`ArtifactPreviewSheet.kt` · `SyntaxHighlighter.kt`

**P0-07｜markdown/span 三套缓存只按条数限流，运行期零释放**（性能#4）
证据：`MarkdownParser.kt:36/38`（300 条 / 4 条）、`MarkdownText.kt:217`（500 条），key 为全文；唯一清理入口是测试用 `clearMarkdownCachesForTest()`；全仓 0 处 `onTrimMemory`，`MemoryWatchdog` 只挂 1 个 releaser（`TianyanApplication.kt:213`）。
影响：单条 128 KB 正文 → 300×128 KB ≈ 38 MB 常驻，`inlineSpanCache` 再叠一份。改法：三个缓存改 `sizeOf` 按字符计费（封顶 8/2/8 MB）+ 注册 `MemoryWatchdog` + 实现 `onTrimMemory`。
文件：`MarkdownParser.kt` · `MarkdownText.kt` · `TianyanApplication.kt` · `MemoryWatchdog.kt`

**P0-08｜服务进程日志每追加一行整表复制**（性能#5）
证据：`ProcessLogStore.kt:28-35` `append` = takeLast(499) + toMutableList + 3 次 `sumOf(String::length)` + `prune()` 全表遍历；`ProcessRegistry.kt:165` 对 `setOf(toolId ?: id, id)` 两个 key 各调一次。
影响：200 行/s 输出 ≈ 1200 次列表拷贝 + 1200 次 StateFlow 发射/s，日志面板持续重组。改法：`ArrayDeque` + `mutableStateListOf` 增量增删，长度计数增量维护，`prune` 交给已有 60 s 定时器。
文件：`ProcessLogStore.kt` · `ProcessRegistry.kt`

**P0-09｜整张消息列表关掉 M3 的 48dp 触控下限，真实按钮 18~28dp**（UI P0-1）
证据：`ChatMessageList.kt:150` `LocalMinimumInteractiveComponentSize provides 0.dp`；受害者 `ChatMessageBubbles.kt:552/557/562`(24dp)、`:705/:727`(18dp)、`:176`(28dp)、`ToolStepsCard.kt:49`(28dp 工具链展开唯一入口)、`:65`(28dp)、`MessageFeedbackAction.kt:65`(24dp)；`RuntimeButtons.kt:129-132` 的 modifier 顺序使补偿失效。
影响：18dp≈3.6mm，TalkBack 触摸探索与单手操作大面积失准。改法：删 `:150` provider + 8 处改 `Modifier.minimumInteractiveComponentSize().size(…)`（**顺序不可反**，范式 `ComposerSendButton.kt:19`、`ChatAttachment.kt:307`），`ToolStepsCard.kt:49` 改 `heightIn(min=48.dp)`。
文件：`ChatMessageList.kt` · `ChatMessageBubbles.kt` · `ToolStepsCard.kt` · `MessageFeedbackAction.kt` · `RuntimeButtons.kt`

**P0-10｜壁纸模式下同列表三种左边缘/三种圆角**（UI P0-2）
证据：`ChatPageBackground.kt:60-71` 给纯文本回复套 28dp 卡 + 16dp 内距、`:74-77` 给 `chatGreetingModifier()` 12/8dp（应用点仅 `ChatWelcome.kt:86`、`ToolStepsCard.kt:48`）；`ChatMessageBubbles.kt:151` 气泡 (24/8)、`ChatToolCards.kt:120` 16dp 不参与。
影响：自定义背景这个卖点功能打开后，滚动时消息块边界来回跳。改法：抽「壁纸模式消息容器」样式供四类消息共用，内缩只作用于背景块，`chatGreetingModifier()` 改名或并入。
文件：`ChatPageBackground.kt` · `ChatWelcome.kt` · `ToolStepsCard.kt` · `ChatMessageBubbles.kt` · `ChatToolCards.kt`

**P0-11｜赞助支持页整链未接线（591 行 + 入口）**（架构 P0-1）
证据：`AppDestinations.kt:25` `SponsorDestination` 无 `entry<>`、无任何跳转；`SponsorScreen.kt:73` 仅被自身 Preview 引用；`SponsorListRepository.kt`/`SponsorListViewModel.kt` 零调用；而 `ARCHITECTURE_RULES.md:35` 仍列为在用页面。
影响：用户永远进不去，文档承诺不可达。改法：`TianyanNavHost` 补 `entry<SponsorDestination>` + 在 `AboutCommunityScreen`/设置首页加入口；不打算上线则整体删除。
文件：`AppDestinations.kt` · `TianyanNavHost.kt` · `SponsorScreen.kt` · `SettingsScreen.kt` 或 `AboutCommunityScreen.kt`

**P0-12｜红队「自动识别目标」整套零调用**（架构 P0-3）
证据：`RedTeamTargetExtractor.kt:13`（`extract`:44 / `defaultScope`:88，111 行含误判防护）全仓 0 调用；配套 `RedTeamConsole.kt:97-98` 的 `autoBindNotice`/`onConsumeAutoBind` 无任何生产者（唯一调用点 `RedTeamWorkbenchSheet.kt:57` 从不传参，永远 null）。
影响：用户必须手填目标，提示卡与提取器全死。改法：在绑定流程前置调用 `extract(text)`，命中写入 `autoBindNotice`（`onConsumeAutoBind` 接 controller 消费）；或删除两者。
文件：`RedTeamTargetExtractor.kt` · `RedTeamConsole.kt` · `RedTeamWorkbenchSheet.kt` · 绑定流程所在（`ChatViewModel.kt` 或 `ChatScreen.kt`）

**P0-13｜CI 只执行 87/698 测试类（12.5%），release 流水线零测试**（质量 P0-1）
证据：`architecture-baseline.yml` 仅 48 条 `--tests` 白名单 / 7 模块；`release.yml:106` 只 `assembleRelease`；`feature/chat`(28)、`tools`(25)、`settings`(20)、`core/model`(18) 等 14 模块测试从未在 CI 跑过。
影响：3888 个用例中约 3100 个永不执行，「有测试」是假安全感。改法：CI 增一次全模块 `testDebugUnitTest`（白名单 job 保留做快反馈），release 前必须全绿。
文件：`.github/workflows/architecture-baseline.yml` · `.github/workflows/release.yml`

**P0-14｜鉴权/凭据/供应链/下载 12 个高风险类零测试**（质量 P0-2）
证据：逐类 grep 0 引用——`McpAuthFilter`、`SafeFileTree`、`OciRegistryClient`、`RootfsInstaller`、`RootfsHttpPolicy`、`TarFileSystemOps`、`ProotMountLayout`、`FileDownloader`、`AppUpdateManager`、`GitCredentialsStore`、`WorkshopKeystore`、`SecretRedactingInterceptor`、`HostShellCommandBuilder`；`SecretManager` 仅被 `@Implements` 打成 mock 桩（`ProviderModelResolverTest.kt:30`）。
影响：坏一次就全盘失效的点位无回归网。改法：先补 6 个纯 JVM 可测项（McpAuthFilter 真值表、SecretManager 往返、SafeFileTree 软链、FileDownloader 重试/超限、AppUpdateManager 解析、`extractHost` 表驱动），1~2 天。
文件：`*/src/test/...`（新增），不改业务源码

**P0-15｜失败被伪装成正常数据（首页指标 / 组件探测）**（质量 P0-4）
证据：`HomeViewModel.kt:224/257` `catch { emptyList() } / { context.filesDir }`；`SystemResourceMetrics:49-64` 默认值编造 `Ubuntu 24.04 LTS`、`proot-distro 5.10.0` 与 0 内存/存储；`WorkspaceViewModel.kt:174` `catch (_: Exception) {}` 吞掉探测失败。
影响：沙箱未就绪时首页仍显示具体数值，诊断失去「不可用」信号；工作区把失败渲染成「所有组件未安装」→ 引导重复安装。改法：指标加 `unknown` 态（null 而非 0）+ 明确文案；探测失败显式 `_error`。
文件：`HomeViewModel.kt` · `SystemResourceMetrics` · `WorkspaceViewModel.kt`

---

## 2. P1 尽快修（归并后 14 条，一条一行）

1. **`ShowerBinderReceiver` exported 无权限**：`AndroidManifest.xml:162-168` + `ShowerBinderReceiver.kt:47-54`（API<34 不校验、34+ 放行 `-1`）→ 任意 App 伪造虚拟屏截图/注入。改法：签名级 permission 或 nonce 握手。（安全#3）
2. **WebView `TianyanBridge` 无来源绑定**：`HookInstaller.kt:38/41-43`（`setOf("*")` 注入所有 frame）+ `TianyanHookBridge.kt:16-23` → 页面可伪造 network/console 事件投毒上下文、`getRules()` 泄露。改法：主帧+源白名单 + per-tab nonce。（安全#4）
3. **全局明文放行 + Provider 无 https 约束**：`tianyan_network_security_config.xml:8` `cleartextTrafficPermitted="true"`（全局）；`ProviderClient.kt:442`、`AnthropicApi.kt:480`、`ResponsesRequestBuilder.kt:180` 无 scheme 校验 + `followRedirects=true` → API Key 明文/降级外泄。改法：`base-config=false` + `requireSecureProviderEndpoint()` + 拒 scheme 降级。（安全#5）
4. **沙箱默认凭据含 `host.install-apk`**：`SandboxCredentialStore.kt:100` + `HostBridgeApkInstall.kt:119-134`（`/workspace/..` 不规范化、else 回宿主绝对路径、同名全树扫描、ADB 静默安装）→ 半信任沙箱内容静默装任意 APK。改法：canonical 包含校验、删 else 与扫描、install-apk 移出默认动作集。（安全#6）
5. **i18n 全仓缺 217 键（非 59）**：zh 2918 / en 2648，扣 assets 样例后缺 217——`feature/git` 71（整模块无 `values-en`）、`bot` 68、`chat` 59、`a2uipoc` 10；另 `RedTeam*.kt` 12 文件 `stringResource` 命中 0。先补 git/bot/chat。（质量 P1-4 + UI P1-1）
6. **653 处静态中文 `Text()` + 129 处模板不在映射表**：`Text("中文")` 1016 处，映射命中 234（settings 321、workflow 149、chat 49）。改法：停止扩张映射表，新码直用 `stringResource`，先批量抽 workflow 149 处。（质量 P1-5）
7. **220 处 `${e.message}` 无 null 兜底 + 247 处空 catch + 257 处 `getOrNull` 静默吞**：样例 `ToolDetailViewModel.kt:199/255/324`、`WorkspaceViewModel.kt:174`。改法：统一 `userMessage(fallbackRes)`；空 catch 至少 `logger.w()`；刷新失败留痕（范式 `StatsScreen.kt:130-152`）。（质量 P1-1/2/3）
8. **字号散落 9~17sp 十档、88 处只改 `fontSize` 不改 `lineHeight`**：`Type.kt:11` 仍是 stock `Typography()`；样例 `ChatPopups.kt:170`(9sp)、`MarkdownText.kt:723/746`(10.5)。改法：建项目自有字阶 + lint 禁 `fontSize=` 字面量。（UI P1-2）
9. **代码块/行内代码/高亮用 GitHub 硬编码色，`code`/`codeSurface` 令牌 0 引用**：`MarkdownText.kt:231-232/699-702`、`SyntaxHighlighter.kt:22-40` 对照 `SemanticColors.kt:47-48`。改法：改用 `semanticColors().code/codeSurface`，深色判定上收 `TianyanTheme`。（UI P1-3）
10. **表格列宽 `widest*7.4dp` 夹逼 56..260dp 且不读 fontScale**：`MarkdownBlockRenderers.kt:47-52/89-97/173-192` → 窄屏必横滑、大字被裁。改法：`Modifier.weight()` 权重分配 + `rememberTextMeasurer()` 实测。（UI P1-4）
11. **骨架屏动画值读在 composition**：`ChatMessageList.kt:371-392`（`:303` 调用），每帧重组 6 个 `Box`。改法：动画值移入 `drawBehind`/`graphicsLayer`；`OrbitalComponents.kt:259-272` 同病。（UI P1-6）
12. **红队面板硬编码绿色深色下 2.8:1（WCAG AA 不过）**：`RedTeamPanel.kt:121/165/246`、`RedTeamConsole.kt:333`。改法：改 `semanticColors().success/warning`（4 处）。（UI P1-7）
13. **每条 shell 命令固定预分配 2 MB×2 + 分块解码截断多字节**：`ProcessShellExecutor.kt:166/184` → `ls` 也 ≈8 MB 瞬时分配；中文/emoji 跨块出现替换字符。改法：初始 8 KB 按需增长 + 复用 `IncrementalUtf8Decoder`。（性能#6）
14. **浏览器 4 项设置只有读侧、Agent 并发上限只有读侧**：`PreferenceFacades.kt:288/289/296/297` 与 `:195` 零调用（读侧 `BrowserMcpBootstrap.kt:89-98`、`SessionTurnCoordinatorImpl.kt:59` 生效）；UI 控件 `ConcurrencySliderRow`（`AgentPreferenceComponents.kt:172`）零调用 → 永远默认值。改法：补 UI 或连同 setter/偏好键一起删。（架构 P1-6/P1-7）

> 其余 P1 未进榜但同属「本轮可做」：`SessionTreeStore` 无 LIMIT 解码 + `SoftReference` 常驻（性能#8）、存储页全量遍历（性能#9）、本机 HTTP 32 MB×8 线程（性能#10）、红队事实流 O(n²)（性能#11）、`RemoteTerminalProcess` `runBlocking`+50 ms 轮询（性能#12）、冷启动 `HarnessLoop` 串行门（性能#13）、`AppUpdateManager` 无大小/摘要/HTTPS（质量 P1-10）、CancellationException 不一致 37 文件（质量 P1-9）。

---

## 3. P2 计划修（按主题归并，共 5 组）

- **死码清理（架构）**：`AnsiTerminalBuffer.kt`(571 行，仅单测) · 群聊工作三件套 762 行（`GroupWorkPanel/GroupWorkSummary/GroupCoworkingDeskBar`）· 聊天工具细节 UI 十件≈799 行（`ToolActivityPill/ToolDetailsSheet/ToolStepHeader/ToolchainStripEntry/ChatProcessStyle/ChatTimelineFocus/ChatUiHelpers/AssistantMessageFooter/SessionsDialog`，`ToolchainStripEntry` 的宿主 `CollapsibleChatWorkbenchStrip` 已不存在）· 8 个零调用小件（`RuntimeBottomBar`、`Breakpoints`、`PackagedPluginInventorySection`、`BrowserActionCard`、`BrowserInitializer`、`EntryTree`、`teamFromRows`、`OAuthRedirectPolicy`）。删除可直接为 77 个「余量=0」文件回血。
- **门禁与棘轮（架构）**：`architecture-policy.json:69` 豁免的 tools/app DAO 直连 6 处（`ToolRepository.kt:3` 等）应收敛；黑名单只匹配 `^import` → 补「源码行内 `core.database.*Dao`」规则；103 条基线中 91 条余量<20、77 条余量=0，`ChatViewModel.kt:168` 已被压成一行三声明。
- **安全加固（P2 8 条）**：`FileProvider` 过宽（`tianyan_file_paths.xml:3-6` `external-path "."`）+ `HostFileActions.kt:25-51` 任意 File 外发且 `filesDir` 附件打不开；`git --output` 绕过免审（`ApprovalPolicyEngine.kt:262-264/253`）；`/api/health` 免认证泄露 root/ADB（`HostBridge.kt:170/195-213`）；一次性票据先删后验（`HostCredentialAuthority.kt:63-67`）；脱敏输出落回 `/workspace/.tianyan-outputs` 可被沙箱读（`ToolExecutor.kt:305-316`）；无 `FLAG_SECURE`；Provider 错误文本未脱敏[疑]（`ProviderClient.kt:766-807`）；`lines` 未入口约束 + 500 泄内部异常 + 未认证 32 MiB 体。
- **内存/分配细节（性能 P2 7 条）**：`inlineSpanCache` key 每帧重拼全文（`MarkdownText.kt:247`）、`BotAvatarImage` key 不计费（`:20-22/56`）、`HarnessBlobStore` 每写编译正则 + 无上限读（`:33-34/51/69-70`）、SSE 多一次整行编码（`McpHttpTransport.kt:564`）、PTY/Web 订阅按块数封顶≈8/16 MB（`NativePtySession.kt:48/123`）、会话级 StateFlow 容器不收缩（`HarnessLoop.kt:131/211`）、`AnsiTerminalBuffer.snapshotLocked` 全量重建（删除则不修）。
- **质量细节（P2 6 条）**：`GitCredentialsStore.extractHost` 对 userinfo URL 解析错（`:74-84`，`https://user:token@…` → `user`）；`ChatMessageNerdLine.kt:132` 漏传格式参数显示 `%1$s`；release 不剥离 `Log`（`proguard-rules.pro` 无 `-assumenosideeffects`，54 文件 304 处裸 Log）；日志写公共 `Download/Tianyan`（`AppLogger.kt:176`）；`release-smoke.yml:63` `-x lint` 且无覆盖率门槛；空态缺失 16/55 个 Lazy 屏 + `ChatErrorBanner` 无重试 + 输入框无长度上限 + 0 个 `androidTest`。

---

## 4. 可直接派给实现代理的批次（批间文件不重叠，可并行）

**批次 A｜安全止损（不含 UI/热路径）**
范围：`keystore.properties`(删)·`tianyan-local.keystore`(删)·`app/build.gradle.kts`(签名段)·`runtime/.../webchat/WebChatBridgeServer.kt`·`WebChatHttp.kt`·`WebTerminalBridge.kt`·`app/src/main/AndroidManifest.xml`·`app/src/main/res/xml/tianyan_network_security_config.xml`·`harness/ProviderClient.kt`·`harness/AnthropicApi.kt`·`ResponsesRequestBuilder.kt`·`runtime/.../virtualdisplay/ShowerBinderReceiver.kt`·`runtime/browser/.../hook/HookInstaller.kt`+`TianyanHookBridge.kt`·`feature/settings/.../CcSwitchSandbox.kt`
交付：P0-01/02/03 + P1 第 1~4 条。**注意**：`ProviderClient.kt` 1291 行余量 2，改动只能净减，或先腾行数。

**批次 B｜对话排版 + 流式热路径 + 内存上限（只碰 feature/chat·components·theme）**
范围：`ChatMessageList.kt`·`ChatMessageBubbles.kt`·`ToolStepsCard.kt`·`MessageFeedbackAction.kt`·`RuntimeButtons.kt`·`ChatTypingIndicator.kt`·`ChatTimelineProjection.kt`·`ChatPageBackground.kt`·`ChatWelcome.kt`·`ChatToolCards.kt`·`MarkdownText.kt`·`MarkdownBlockRenderers.kt`·`MarkdownParser.kt`·`SyntaxHighlighter.kt`·`ArtifactPreviewSheet.kt`·`Type.kt`·`SemanticColors.kt`·`RedTeamPanel.kt`·`RedTeamConsole.kt`(仅 :333 色)·`TianyanApplication.kt`·`MemoryWatchdog.kt`·`ProcessLogStore.kt`·`ProcessRegistry.kt`
交付：P0-04/05/06/07/08/09/10 + P1 第 8~12 条。**注意**：`ChatMessageList.kt` 912 行、`ChatMessageBubbles.kt` 1077 行、`MarkdownText.kt` 797 行都在棘轮上，需边改边减。

**批次 C｜死码删除（纯删除，腾棘轮额度，必须最先合入以解冻 B）**
范围：`AppDestinations.kt`(仅删 `SponsorDestination` 行)·`SponsorScreen.kt`(删)·`SponsorScreenPreviews.kt`(删)·`SponsorListRepository.kt`(删)·`SponsorListViewModel.kt`(删)·`runtime/.../terminal/AnsiTerminalBuffer.kt`(删)+对应测试·`GroupWorkPanel.kt`/`GroupWorkSummary.kt`/`GroupCoworkingDeskBar.kt`(删)+测试·`ToolActivityPill.kt`/`ToolDetailsSheet.kt`/`ToolStepHeader.kt`/`ToolchainStripEntry.kt`/`ChatProcessStyle.kt`/`ChatTimelineFocus.kt`/`ChatUiHelpers.kt`/`AssistantMessageFooter.kt`(删)·`ChatDialogs.kt`(仅删 `SessionsDialog`)·`RuntimeComponents.kt`(仅删 `RuntimeBottomBar`)·`Dimens.kt`(仅删 `Breakpoints`/`Touch`/`Content` 未用项)·`PackagedPluginInventorySection.kt`/`BrowserActionCard.kt`/`BrowserInitializer.kt`(删)·`EntryTree.kt`+测试(删)·`GroupTeamMapping.kt`(仅 `teamFromRows`)·然后 `architectureBaselineSync` 下调基线。
交付：P2 死码组。**这是唯一能让 77 个冻结文件回血的批次，建议第一批合入。**

**批次 D｜接线修复（入口与设置回接）**
范围：`feature/navigation/.../AppDestinations.kt`(仅当选择「接线」而非「删除」赞助页时)·`TianyanNavHost.kt`·`SettingsScreen.kt`/`AboutCommunityScreen.kt`(赞助入口)·`RedTeamWorkbenchSheet.kt`·`ChatScreen.kt`(绑定流程)·`PreferenceFacades.kt`·`BrowserMcpBootstrap.kt`/`McpServerModule.kt`(只读侧不动)·`AgentPreferenceComponents.kt`·`AgentSettingsScreen.kt`(挂并发控件)
交付：P0-11/12 + P1-14。
**冲突消解**：批次 C 与 D 都涉及「赞助页」与 `AppDestinations.kt`——二选一：走**删除**则 C 负责、D 跳过；走**接线**则 D 负责、C 跳过。`RedTeamTargetExtractor.kt` 归 D（接线），C 不得删。

**批次 E（附加，与 A–D 无文件交集）｜测试与 CI**
范围：`.github/workflows/*.yml`·`*/src/test/**`(新增用例)·`app/proguard-rules.pro`
交付：P0-13/14 + 质量 P2-3/P2-5。可与 A–D 同时开工。

---

## 5. 本轮不建议动（风险高 / 收益低 / 证据不足）

1. **棘轮余量=0 的 77 个文件做结构大改**：任何净增一行都失败，且 2565 行/1555 行是两个 god class 的载体；必须先走批次 C 腾额度，否则实现代理会卡在 CI。
2. **`RedTeamCoordinator.kt`（2565 行、余量 1）拆分**：红队是主推能力、无回归网（`RedTeamCoordinator` 零测试），拆分与线上面行为强耦合；等批次 E 有测试网后再动。
3. **`CursorWindow` 100 MB 调参**（`TianyanApplication.kt:224-232`）：正确修法是「给 `red_team_facts.payload` 等列加写入期长度校验 + 外置化」，需真机测 ashmem/RSS，静态审计给不出安全阈值，现改可能把 OOM 变成慢查询。
4. **`SessionTreeStore` 加 LIMIT / 缓存改 WeakReference**（性能#8）：需先复现 2026-09-14 类 OOM 的真实会话规模；`branch()` 递归 CTE 改动牵动 `search`/`read` 两个 Agent 工具入口，误改会让 Agent 读不到历史。
5. **653 处静态中文全量抽键**：量太大且映射表方案本身要被废弃（质量 P1-5 结论）；本轮只做 `feature/git` 整模块 + workflow 149 处，全量迁移另立项目。
6. **`flake` 率未知的 UI 微调**：UI 报告「待确认」三项（top 64 vs 72dp、非 4:3 图片跳变、Coil3 头信息回调）未坐实，改之前需真机截图对比，不宜按报告猜测动代码。

---

## 6. 口径冲突与待确认

- **i18n 缺键数：59 vs 217**（已定 217）。UI 报的 59 = `feature/chat/src/main/res/values/strings.xml` 这一份；质量报告比对**全部** `res/values/*.xml` 得 zh 2918 / en 2648，扣 `assets/plugins`(51) 与 `project-template`(3) 后应用代码缺 217（git 71 / bot 68 / chat 59 / a2uipoc 10 / harness 7 / workspace 2）。**采用 217**；若只做对话模块，59 是该模块的局部口径。
- **中文硬编码：1016 vs 653+129**（已定 653 静态 + 129 模板）。1016 是 `Text("中文")` 全仓命中；其中 234 处已被 `LocalizedText` 映射覆盖（settings 39 个文件用 `import …LocalizedText as Text` 别名），未命中 782 = 653 静态 + 129 含模板变量。**采用 653+129**。
- UI 待确认（需真机，不据此动工）：① `ChatMessageList.kt:158` top=statusBars+64dp 与 `:295` 渐隐 top=statusBars+72dp 的 8dp 差是否为有意；② `MarkdownText.kt:363-373` 非 4:3 图片高度跳变（竖图 195→340dp，注释自承是已知取舍）需确认 Coil3 能否在头信息阶段回调 intrinsic size；③ `PulseDot` 的"GPU 开销可忽略"注释只对 `graphicsLayer` 成立，需实测。
- 安全 × 文档冲突：修安全 P1-6（`resolveSandboxPath` 白名单化）会与 `docs/SECURITY_SURFACE.md` §4 风险 #3 的既有定性冲突，需同步更新该文档（其第 212 行有维护约定）。
- 安全 P2-9（Provider 错误文本）标 `[疑]`：未整读 `ProviderClient.kt`，需确认异常路径是否已过 `SecretRedactor` 再决定改法。
