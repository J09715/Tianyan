# 天衍 · 性能 / 内存 / 协程 审计（perf-auditor）

只读审计，未修改任何业务源码。范围：app、runtime（PRoot/RootFS/PTY/工作区）、harness（Agent 循环/MCP/子智能体）、core（database/network/datastore）、feature/terminal + feature/chat 热路径。
每条 = 位置 + 机理 + 可量化影响 + 改法。**已剔除上游已确认结论**（AnsiTerminalBuffer 零调用、ChatViewModel 39 依赖、RedTeamCoordinator 行数余量）不再重复举证。

---

## P0（在线热路径，直接影响每帧/每 token）

### 1. 流式生成期间每 token 全量物化消息正文两次（`ChatMessageList.kt:139`、`ChatTypingIndicator.kt:31`）
`AssistantText.text` 是 getter，内部 `textContent.toString()` 把 ChunkedText 的**全部 blocks 拼成一个 String**（`HarnessMessage.kt:194`；`ChunkedText.kt:62`）。`ChatMessageList.kt:139` 把它塞进 `remember` key（`"${last.text.length}:…"`），`ChatTypingIndicator.kt:31` 又调 `message.text.isNotBlank()`，两处都在每次重组时执行。
影响：每条新快照 `buildString(length)` 复制整段正文；30 KB 回复按 30 token/s ≈ **900 KB/s**，100 KB 回复 ≈ **3 MB/s** 纯垃圾，且 key 变化 → `renderItems` 必失效。注意 `ChunkedText.length`（`ChunkedText.kt:16`）与 `hasText` 都是 O(1)，本不需要物化。
改法：`:139` 改 `last.textContent.length`；`:31` 改 `message.textContent.hasText`。行为等价，零分配。

### 2. 每 token 全量重投影消息列表（`ChatMessageList.kt:126`）
`rememberChatRenderItems(messages, …)` 以整个 `messages` 为 key，一次投影走 `projectChatMessages → splitReasoning → groupToolSteps` 三趟全量 buildList（`ChatTimelineProjection.kt:38,45,62`），600 条窗口上限下每 token 重建 600+ 个 item 对象并逐项 `copy()`。
影响：30 token/s × 600 item ≈ **1.8 万次分配/s**，并让 LazyColumn 的 `key = item.stableKey` 全部走一遍 diff；这是「流式时列表卡」的主因。
改法：流式期只重投影末条（把不可变前缀投影结果缓存，末条单独投影后拼接）；或把流式文本移出 `messages` 列表走独立 `State<ChunkedText>` 通道。

### 3. 代码预览把「行号栏」渲染成 N 个 Text，且全在一个 `verticalScroll` Column 里（`ArtifactPreviewSheet.kt:306-346`）
`lines.indices.forEach { Text("${idx+1}") }`（:326-336）在非懒加载容器内一次组合全部行；正文是单个可达 `MAX_HIGHLIGHT_LENGTH = 500 KB` 的 AnnotatedString（`SyntaxHighlighter.kt:256`）。
影响：1 万行文件 → **1 万个 Text 节点 + 1 万次 measure/layout** 在同帧完成（数百 ms~秒级冻结），此后每次滚动仍对全部节点做 layout；500 KB 单 Text 的 StaticLayout 再加一份同量级 span。
改法：行号与正文合并为 `LazyColumn`（一条 item = 一行，`items(lineCount)`），或外侧用 `LazyRow`+`verticalScroll`；超 5 千行只渲染可视区。

### 4. 三套全局 markdown/span 缓存只有条数上限，运行时没有任何释放路径（`MarkdownParser.kt:36,38`、`MarkdownText.kt:217`）
`markdownBlockCache(300 条)` / `largeMarkdownBlockCache(4 条)` / `inlineSpanCache(500 条)` 全是进程级 static，key 是**全文**，唯一清理入口是测试用的 `clearMarkdownCachesForTest()`（`MarkdownText.kt:794`、`MarkdownParser.kt:294`）。
影响：单条 128 KB 正文（`MARKDOWN_CACHE_MAX_CHARS`，`:292`）可让 `markdownBlockCache` 常驻 **300×128 KB ≈ 38 MB**（key 一份 + MdBlock 内 substring 一份），`inlineSpanCache` 再叠 500×(正文+span)；全仓 **0 处 `onTrimMemory`/`onLowMemory`**，`MemoryWatchdog` 只注册了 1 个 releaser（`TianyanApplication.kt:213`）。
改法：三个缓存都改 `sizeOf` 按字符数计费（总量封顶 8/2/8 MB）；把 `evictAll()` 注册进 `MemoryWatchdog.registerReleaser`，并实现 `Application.onTrimMemory`。

### 5. 服务进程日志每追加一行就整表复制（`ProcessLogStore.kt:28-35`）
`append` = `flow.value.takeLast(499) + line` → `toMutableList()` → `sumOf(String::length)` → `replace()` 里**再算两次** `sumOf` → `prune()` 每次全表遍历。单次 O(3n) 列表拷贝 + 3 次全量长度求和，且每次都向 `MutableStateFlow` 发一个新 List。
影响：`ProcessRegistry.readLogs`（`ProcessRegistry.kt:165`）对 `setOf(toolId ?: id, id)` **两个 key 各调一次**（不同时 2 倍）；`npm run dev`/`vite` 这类 200 行/s 输出 ≈ **1200 次列表拷贝 + 1200 次 StateFlow 发射/s**，日志面板持续重组。
改法：日志改 `ArrayDeque<String>` + `mutableStateListOf`（增量 add/removeFirst），长度计数增量维护，`prune` 抽到 60s 定时器（`ProcessRegistry.kt:122-128` 已有）。

---

## P1（明确的内存/阻塞问题，需排期）

### 6. 每条 shell 命令固定预分配 2 MB×2 缓冲，且分块解码 UTF-8 会截断多字节字符（`ProcessShellExecutor.kt:166,184`）
`ByteArrayOutputStream(MAX_CAPTURE_BYTES=2 MB)` 在 `readFully` 里为 stdout/stderr 各建一个（`ByteArrayOutputStream` 立即分配底层数组），之后 `kept.toString(UTF-8)`（约 2 MB 字符）+ `buildString` 再复制一次。
影响：`ls` 这类 20 字节输出也要 **≈8 MB 瞬时分配**；Agent 一轮跑几十条命令、并行分派（`Semaphore(parallelism)`）时是内存尖峰主源。`String(buffer,0,read,UTF_8)` 逐 16 KB 块解码，中文/emoji 跨块即出现替换字符。
改法：初始容量给 8 KB、按需增长（保留 2 MB 硬顶）；解码改用仓库已有的 `IncrementalUtf8Decoder`（`runtime/pty/IncrementalUtf8Decoder.kt`）。

### 7. 全局 CursorWindow 上限被抬到 100 MB，去掉了大行保护（`TianyanApplication.kt:224-232`）
反射改 `CursorWindow.sCursorWindowSize` 2 MB→100 MB。大载荷外置只覆盖 `harness_entries.payloadJson`（`HarnessBlobStore.kt:74`，48 KB 阈值），`red_team_facts.payload`、构建脚本、审批 JSON 等列**没有任何尺寸上限**（`RedTeamFactRepository.kt:34` 只限条数 500）。
影响：一条 20 MB 的行现在能整块进 CursorWindow（ashmem）+ 同等 Char 的 String（UTF-16 再翻倍），单次查询可吃掉几十 MB；`MemoryWatchdog` 只采样 Java 堆（`MemoryWatchdog.kt:71-77`），ashmem/RSS 完全不可见 → 直接 OOM 而非先降级。
改法：保留 100 MB 但给 `harness_usage`/`red_team_facts` 等加写入期长度校验与外置化；把 `Debug.getNativeHeapAllocatedSize`/`Debug.MemoryInfo` 纳入 watchdog 采样。

### 8. `SessionTreeStore` 的解码分支缓存无上限、且走无 LIMIT 的 `branch()`
`decodedBranch`（`SessionTreeStore.kt:44-56`）调 `repository.branch` → `HarnessBranchQueries.kt:8-17` 的递归 CTE **没有 LIMIT**，再把每条 payload 从 blob 文件读回并 JSON 解码；结果放进 4 槽 `LinkedHashMap` 的 **`SoftReference`**（GC 不到最后不回收）。
影响：一次 `search`/`read`（`:206,231,242`，都是 Agent 工具入口）就能把整个会话历史（含每条 ≤48 KB 内联 + 外置文件）解码进堆；4 个会话 ≈ 数百 MB 常驻，正是 2026-09-14 OOM 的同类形态（那次修的是批量统计路径，读取路径仍在）。
改法：搜索/读取改走 `branchTail(limit)` + SQL `LIKE`（`searchBranch` 已存在，`HarnessBranchQueries.kt:73-85`）；缓存改 `WeakReference` + 总字符预算。

### 9. 存储页打开即全量遍历所有受管根目录，且逐文件做线性规则匹配 + 额外 syscall（`StorageManager.kt:100,143-152,351-356`）
`inspect()` 对 filesDir/workspace/distros/cache 各 `walkFileTree`（无缓存、无增量），每文件 `rules.firstOrNull { file.startsWith(it.root) }` 线性比较 ~20 条规则，再 `eligible()` 里 `relative.any { it.toString() in setOf("partial",".git") }`（**每个路径段新建一个 Set + 一个 String**），对可清理文件还额外 `singleLink()` 一次 `Files.getAttribute`。
影响：10 万文件规模 ≈ **200 万次路径比较 + 数十万次 set 分配 + 10 万次额外 syscall**，数秒级；`StorageUsageViewModel.init`（`:63`）立即触发，且每次清理动作后再全量重扫（`:120/153/180/207/234` 五处）。
改法：规则按 `root.nameCount` 降序首匹配（已是降序排序，改成前缀索引或 memo）；`setOf` 提为 companion 常量；`singleLink` 只在真正要删时查；扫描结果按目录 mtime 做增量。

### 10. 本地 HTTP 端点（WebChat / Web 终端 / MCP）把请求体整段读成 String，单请求上限 32 MB、8 线程并发（`AndroidHttpServer.kt:171-181,253`、`WebTerminalBridge.kt:315`、`WebChatHttp.kt:65`）
`ByteArray(contentLength)` 按 Content-Length 直接分配（≤32 MB），随后 `bufferedReader().readText()` 再复制一份字符（≤64 MB 字符板）。
影响：8 个 worker × 32 MB = **256 MB 瞬时**可达；服务监听通配地址（`WebChatBridgeServer.kt:110` `InetSocketAddress(port)`）+ `Access-Control-Allow-Origin: *`，同网段即可触发。
改法：`MAX_BODY_BYTES` 降到 1–2 MB（JSON 控制面不需要更大）；用流式 `Json.decodeFromStream` 替代 `readText()`；绑定 `127.0.0.1`。

### 11. 红队事实流无分页、每次写入触发全表重读（`RedTeamFactEntity.kt:33-34` ← `RedTeamWorkbenchSheet.kt:48`）
`observeForSession` 是 `SELECT *`（含 `payload` JSON）无 LIMIT 的 Room Flow；`upsertAll` 每次写 500 条即触发一次失效重查。
影响：400 资产 + 端口/漏洞事实的演练里，写 N 次就读 N 次全量 → **O(n²) 读取量**（数千行 × 每行 JSON），面板每次 `collectAsState` 全量重组；`byKinds(limit)`（`:47`）说明分页能力已具备但这条流没用。
改法：该流改为「摘要列投影 + `LIMIT`」，payload 单独按需取；或写入侧聚合节流（200 ms 合并一次 invalidate）。

### 12. 远程终端适配器用 `runBlocking` + 50 ms 轮询驱动 SSH 会话（`RemoteTerminalProcess.kt:64,72,77,82`）
`write()` 直接 `runBlocking { session.write(...) }`，无超时；`waitForExit()` 是 `runBlocking { while (isAlive) delay(50) }`；`kill()/close()` 同样 `runBlocking`。
影响：SSH 连接假死（TCP 半开、弱网）时输入线程被永久阻塞，键盘无回显且无法中断；每个远程会话额外 **20 次/s 唤醒**（Doze 下也不停）。
改法：`write` 入队到该会话的写协程（有界 Channel）+ `withTimeout`；`waitForExit` 改由 `session.isAlive` 的 StateFlow/回调驱动，去掉轮询。

### 13. 冷启动把 `HarnessLoop` 构造排在本地服务监听之后（`TianyanApplication.kt:103-190`）
同一个 `Dispatchers.IO` 协程里 `coroutineScope { … }` 先 await `browserMcpBootstrap.bootstrap()`（内部 `runBlocking`，`BrowserMcpBootstrap.kt:87`）、`agentMcpBootstrap.start()`、技能目录全量扫描 `syncFromDirectories`（`:123`），完成后才 `harnessLoopLazy.value`。
影响：首轮对话可用时间 = 本地端口监听 + 引擎注册 + 技能目录扫描（`attachments`+`workspace` 递归）之和；技能目录在千级文件时是秒级，这段完全串在关键路径上。
改法：只对「MCP 预设入库」建立 `CompletableDeferred` 信号量，`HarnessLoop` 构造等该信号量而非等全部任务；技能扫描移到 `bootstrap` 之后独立协程。

---

## P2（值得清理，非紧急）

### 14. `inlineSpanCache` 的 key 是「样式值 + 全文」拼接，命中也要重新拼一遍（`MarkdownText.kt:247`）
每次 `buildInline` 都执行 `"$forceBold|${linkColor.value}|…|$text"`，即使命中缓存也已分配了一个 N 字符的字符串。
影响：滚动/流式时单条 100 KB 消息 ≈ **每帧 100 KB** 新分配。
改法：key 改 `Triple(styleHash, isDark, textIdentity)` 或用 `IdentityHashMap`/弱引用按 text 实例缓存；最省事是 `key = text.hashCode() xor styleHash` 并复核相等。

### 15. `BotAvatarImage` 的 LruCache 把「≤400 KB 的 base64 全文」当 key，只按位图字节计费（`BotAvatarImage.kt:20-22,56`）
`sizeOf` 只返回 `bitmap.allocationByteCount`，key（整段 data URL）不进预算。
影响：4 MB 预算下可积累几十个头像，**未被计费的 key 文本可达 10 MB 级**，且这批 String 还被消息对象同时持有。
改法：key 改 `seed.hashCode()`（同尺寸原图已校验）或 `sizeOf = bitmap.bytes + key.length*2`。

### 16. `HarnessBlobStore` 每次写入都给 sessionId/entryId 各编译一次正则，读取整文件进 String（`HarnessBlobStore.kt:33-34,51,69-70`）
`sanitizeFileName` 内 `Regex("[^a-zA-Z0-9._-]")` 每次调用重新编译（`storeIfLarge` 调 2 次）。
影响：一次对话几千条 entry → 上万个 Regex 编译（每次数十 µs）；`read()` 无尺寸上限，10 MB 外置文件一次读成 String。
改法：正则提为 `companion object` 常量；`read()` 加 `MAX_BLOB_BYTES` 上限并对超限只回占位。

### 17. SSE 解析为「统计一行字节数」而整行再编码一次（`McpHttpTransport.kt:564`）
`eventBytes += line.toByteArray(Charsets.UTF_8).size + 1`：`readUtf8LineStrict` 已经产出 String，这里再复制一份字节数组。
影响：单条 1 MiB data 行 → **额外 1 MiB 垃圾**，MCP 大结果回流时成倍。
改法：用 `line.length * 3` 作上界估算（保守），或让 `MAX_SSE_LINE_BYTES` 在读取层直接封顶。

### 18. PTY 与 Web 终端订阅缓冲按「块数」而非字节封顶（`NativePtySession.kt:48,123`、`WebTerminalBridge.kt:225,354`）
`OUTPUT_BUFFER_CAPACITY = 1024` 块 × ≤8 KB ≈ **8 MB**/会话；`LISTENER_BUFFER = 2048` ≈ **16 MB**/订阅者。
影响：1 个本地终端 + 1 个 Web 订阅 + Termux 2000 行 transcript（`TerminalProcess.kt:26`）≈ 30 MB；慢客户端多开即线性叠加。
改法：改为字节额度（如 512 KB/会话、2 MB/订阅者），超限按注释里的策略断开（逻辑已具备，只改常数语义）。

### 19. 每会话两份 `MutableStateFlow` 容器永不收缩（`HarnessLoop.kt:131,211`、`SessionStateMirrors.kt:47-48,58`）
`_sessionPendingMessages`/`sessionErrors`/`sessionThinkingLives` 只在删会话时替换值（`:334`）而非 `remove`，`sessionJobs`（`:127`）在取消路径下可能残留已完成 Job（只在 `finishRun` `:848` 移除）。
影响：bot/群聊场景会话数上百时，每个残留项 ≈ 数百字节 + 一次 `Map` 全量 copy 的 `update{}` 开销；量级不大但属确定性泄漏。
改法：`removeSession` 里补 `remove()`；`launchSessionJob` 统一 `invokeOnCompletion { sessionJobs.remove(id, job) }`。

### 20. `AnsiTerminalBuffer.snapshotLocked` 每次 append 全量重建（`AnsiTerminalBuffer.kt:128-142`）
`(0 until endExclusive).map { ArrayList(row) + subList().toList() }`：2000 行 × 120 列 → 每次 append 复制 24 万个 cell 引用 + 2000 个 `TerminalLine`。
影响：当前**零生产调用**（仅 `runtime/src/test/.../AnsiTerminalBufferTest.kt`），不构成线上问题；一旦接入富终端渲染就是 O(全屏) per chunk 的分配风暴。
改法：留给后续决定——要么删除（571 行零调用），要么改增量快照（只重建脏行）。

---

## 最值得先修的 5 条

1. **#1 流式每 token 全量物化正文**（`ChatMessageList.kt:139` + `ChatTypingIndicator.kt:31`）— 两个字符的改动（`text` → `textContent.length` / `textContent.hasText`），直接消掉 MB/s 级垃圾，风险最低收益最高。
2. **#2 每 token 全量重投影**（`ChatMessageList.kt:126`）— 流式卡顿主因，前缀缓存 + 末条单投影即可把每 token 的 600 项工作降到 1 项。
3. **#3 代码预览非懒加载**（`ArtifactPreviewSheet.kt:306-346`）— 万行文件必冻结，改 `LazyColumn` 是明确的 ANR 级修复。
4. **#4 markdown/span 三缓存无字节上限且无 trim 回调**（`MarkdownParser.kt:36,38`、`MarkdownText.kt:217`）— 长会话常驻内存的主嫌疑，`sizeOf` + watchdog releaser + `onTrimMemory` 三件套一次补齐。
5. **#6 每条命令 8 MB 瞬时分摊**（`ProcessShellExecutor.kt:166,184`）— Agent 工具密集期内存尖峰主源，顺带修掉中文输出跨块截断的正确性缺陷。

> 补充口径：#5（进程日志 O(n) 复制）与 #7（100 MB CursorWindow）紧随其后；若目标只是「先让流式顺滑 + 不再 OOM」，1–4 + 6 足够。
