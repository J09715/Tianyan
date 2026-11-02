# 安全面审计（P0/P1/P2）

> 目标：`tianyan-latest`（只读审计，未改业务源码）。基线：`docs/SECURITY_SURFACE.md`（已读，其「PRoot 非安全边界」定性不再重报）。
> 标注：`[验]`=代码已坐实；`[疑]`=有一环未坐实。设计取舍不计漏洞。

## P0（2）

**1. 发行签名密钥 + 口令随工作树交付** `[验]`
证据：`keystore.properties:1-4` 明文 `storePassword=tianyan2026`；`tianyan-local.keystore` 同目录。`apksigner verify --print-certs app/build/outputs/apk/release/tianyan-v1.5.14-release.apk` → `SHA-256 29ba3b63…97`，与 `keytool -list -v -keystore tianyan-local.keystore` 指纹一致（该密钥确签 release）。两者被 `.gitignore:24-25` 忽略、未入库，但存在于每个复制/共享的工作树副本（本次交付即带）。
攻击路径：拿到目录副本 → 用同私钥签 `versionCode` 更高的 APK → Android 视为同签名升级，静默覆盖官方包。影响：发行链失守（若该密钥亦用于对外发布，等同私钥泄露）。
修复：交付物删两文件、口令改 `TIANYAN_RELEASE_*` 环境变量（`app/build.gradle.kts:42-53` 已支持）、凡该密钥签过的对外包按泄露流程轮换 + CI 拦截 `*.keystore` 入库/入档。

**2. WebChat 局域网桥：6 位 PIN 无速率限制 + CORS `*` → Agent/终端接管** `[验]`
证据：`runtime/.../webchat/WebChatBridgeServer.kt:110` `InetSocketAddress(port)`（通配 0.0.0.0）→ `AndroidHttpServer.kt:69-70` 直绑；`:595` PIN `(100000..999999)`（10^6）、`:225` 明文比较、`:538-543` 接受 query `token`；`WebChatHttp.kt:56-60` 对**含 401 的所有响应**加 `Access-Control-Allow-Origin: *`（`WebTerminalBridge.kt:329` 同）；全目录 grep `attempt|throttle|lockout|rateLimit` **零命中**。
攻击路径：同 Wi-Fi（或同机任意 App / 手机浏览器任意网页）并发枚举 PIN（单请求仅一次比较、无锁无退避，≥200/s 期望 ~42min）→ 拿到 `/workspaces/file`(GET/PUT)、`/terminal*` PTY、对话 API → 发 prompt 驱动 Agent（默认 `FULL_ACCESS`，`ToolExecutor.kt:162`，宿主 `exec` 不弹审批）。
影响：局域网/跨源一步到「guest 任意命令 + Agent 全权 + 工作区任意读写」。修复：PIN 限速+锁定并加长、CORS 白名单化、token 只走 `Authorization`、默认绑 loopback、桥接会话强制 `ASSISTED`。

## P1（4）

**3. exported `ShowerBinderReceiver` 接受任意应用伪造 Binder** `[验]`
证据：`AndroidManifest.xml:162-168` exported=true 且无 `android:permission`；`ShowerBinderReceiver.kt:47-54` API<34 **完全不校验**来源（minSdk 29），API34+ 放行 `sentFromUid == -1`（注释自承真机恒为 -1）；`:63-67` 直接注册为全局 `IShowerService`（含 `requestScreenshot`/`injectTouchEvent`/`setVideoSink`）。
攻击路径：普通 App 发该 action 广播带自实现 Binder → 虚拟屏截图返回伪造画面、真实注入坐标外泄。影响：模型「看屏」被投毒（等价画面级提示词注入）。修复：加签名级自定义 permission，或 nonce 握手（server 启动时 App 下发）+ 拒绝 -1。

**4. WebView `TianyanBridge` 无来源绑定 → 页面可污染模型上下文** `[验]`
证据：`HookInstaller.kt:38` `addJavascriptInterface(..., "TianyanBridge")`、`:41-43` document-start 脚本 `setOf("*")` 注入所有 frame；`TianyanHookBridge.kt:16-23` 无 origin/主帧校验；`HookEventPipeline.kt:83-120` 直接发布为 `NetworkCaptured/HookHit`，而 `browser_network_list`/`browser_console_list` 免审（`TrustedToolRiskPolicy.kt:35-37`）；`getRules()` 回吐用户 hook 规则。
攻击路径：Agent 打开任意站点/iframe → 页面 JS 调 `onEvent` 伪造抓包（如假 401、假 token）→ 模型据此决策。影响：浏览器侧提示词注入 + 规则泄露。修复：主帧+来源白名单校验、per-tab nonce、事件标注「页面自述」。

**5. 全局明文放行 + Provider `baseUrl` 无 https 约束** `[验]`
证据：`tianyan_network_security_config.xml:8` `base-config cleartextTrafficPermitted="true"`（全局，`:11-16` 的 domain-config 因此冗余）；`ProviderClient.kt:442`、`AnthropicApi.kt:480`、`ResponsesRequestBuilder.kt:180` 直接拼 `model.baseUrl`，**无 scheme 校验**；`HttpClientProvider.kt:10-24` 默认 `followRedirects=true`（无 trustAll，见 §3）。
攻击路径：用户填 `http://` 自建中转（或 https 端被 302 降级到 http，OkHttp 同 host 降级保留鉴权头，系统明文守卫又被全局关掉）。影响：`Authorization: Bearer <Key>` 明文外泄。修复：`base-config` 改 false；Provider 加 `requireSecureProviderEndpoint()`（对齐 `McpHttpEndpoints.kt:27-33`）；拒绝 scheme 降级重定向。

**6. 沙箱默认凭据含 `host.install-apk`，安装路径无目录白名单** `[验]`
证据：`SandboxCredentialStore.kt:100` `SANDBOX_ACTIONS = {install-apk, logcat}` 经 `TIANYAN_BRIDGE_TOKEN` 注入 guest（同 uid 可读）；`HostBridgeApkInstall.kt:119-120` `/workspace/..` → `File(workspaceDir, …)` **不规范化**，`:127-134` else 分支「原样返回宿主绝对路径」（仅 `.apk` 后缀校验），`:55-66` 无线 ADB 已连则**静默安装**，`:36-40` 工作区全树同名兜底扫描。
攻击路径：沙箱内半信任内容（MCP STDIO server / `npm install` 脚本 / 第三方 skill）读 token → `/api/install-apk` 指向任意宿主路径。影响：内容物→静默装任意 APK（无系统确认）。修复：三映射 canonical 包含校验、删 else 与全树扫描、install-apk 移出默认动作集（同 `host.shell` 需原生确认）。

## P2（8）

**7. FileProvider 过宽 + 任意 `File` 可外发** `[验]`：`tianyan_file_paths.xml:3-6` 含 `external-path "."`（整盘共享存储）；`HostFileActions.kt:25-51` 接受任意 `File` + 读授权拉起 View/Send → 用户点一次即把 `/sdcard` 任意文件授权给任意 App。顺带：附件在 `filesDir/...` 而 paths 无 `<files-path>`，`AttachmentOpener.kt:15-20` 必然失败。修复：收敛到具体子目录、加 canonical 根校验、补 files-path。

**8. 免审白名单被 `git` 写文件选项绕过** `[验/需按版本复现]`：`ApprovalPolicyEngine.kt:262-264` 仅锚前缀，`:253` 黑名单不含 `--output/-O/--ext-diff` → `git diff --output=/x`（`:378-387` 判免审）免审落盘，破坏「写操作需确认」语义（不越沙箱边界）。修复：git 子命令参数级黑名单，`READ_ONLY_PRIMARY` 同步。

**9. Provider 错误文本未脱敏** `[疑]`：`ProviderClient.kt:766-807` 把远端 `error.message`/body 前 300 字符拼进异常（`:52-57` 另含 body 前 512 字符），进 UI 错误卡与历史；工具输出有 `SecretRedactor`，此路径没有。若中转回显 Authorization 即明文泄露。修复：异常消息统一过 `SecretRedactor` 并截断。

**10. `/api/health` 免认证** `[验，已登记 doc §7#2]`：`HostBridge.kt:170` 先于鉴权返回 root/Shizuku/ADB 状态与 `adbPort`（`:195-213`）→ 同机任意 App 与沙箱可零成本指纹化后选攻击路径。修复：只回 `{"status":"ok"}`，详情走鉴权端点。

**11. 一次性票据在动作不匹配时即被消费** `[验]`：`HostCredentialAuthority.kt:63-67,78-81` `begin()` 先删票再校验 action/有效期 → 插件 10 秒单次票据拼错参数即报废（亦可被同 uid 用作 DoS）。修复：先 `valid()` 再消费。

**12. 脱敏后的长输出落回工作区，沙箱可读** `[验]`：`ToolExecutor.kt:305-316` 写 `.tianyan-outputs/`，该目录即 PRoot `/workspace` 挂载（`ProotCommandBuilder.kt:60`）→ `SecretRedactor`（`SecretRedactor.kt:9-24` 启发式，无标签随机串漏网）漏掉的密钥被二次落盘给 guest 任意进程读。修复：归档移出工作区（或标注「脱敏副本」并限制挂载）。

**13. 无 `FLAG_SECURE`** `[验，全仓零命中]`：设置页「显示 API Key」与通知里的 WebChat PIN（`WebChatBridgeServer.kt:127`）可被录屏/投屏/无障碍截图采集。修复：密钥页与配对页 `window.setFlags(FLAG_SECURE)`。

**14. 轻微加固** `[验]`：`HostBridge.kt:285` 的 `lines` 未在入口 `coerceIn`（靠 `EmbeddedAdbManager.kt:483` 兜底）；`HostBridge.kt:160` / `WebChatBridgeServer.kt:427` 500 回包带内部异常文本；`AndroidHttpServer.kt:170-175` 未认证即可 POST 32 MiB 体。修复：入口校验 + 固定文案 + 鉴权前限 64 KiB。

## 已核实无问题（不重复报）

① `SecretManager.kt:14-30` AES/GCM+AndroidKeyStore，无明文回退；`SettingsDataStore.kt:531`、`McpServerEntity.kt:234-236`、`RemoteServerRepository.kt:82`、`McpOAuthEntity.kt:122-144`、`GitCredentialsStore.kt:53` 全密文。
② `AndroidManifest.xml:57` `allowBackup=false`，敏感存档走 `noBackupFilesDir`（`EncryptedRequestDiagnosticsRepository.kt:32-46`）。
③ 全仓无 `trustAll/sslVerify/自定义 TrustManager`；MCP 明文限 localhost/192.168.*（`McpHttpEndpoints.kt:27-33`）、OAuth 强制 https（`OAuthPkce.kt:47-55`）、下载强制 https（`FileDownloader.kt:53-55`）。
④ OAuth deeplink 校验 state+redirect 匹配+PKCE+原子消费（`MainActivity.kt:342-355`、`McpOAuthCoordinator.kt:74-99`），无 code 注入。
⑤ 路径边界：`WorkspaceFileAccess.kt:314-331` / `WorkspaceFileService.kt:141-166` 拒绝 `..` 与逃逸绝对路径 + canonical 包含校验；`WebChatBridgeServer.kt:480-484` 下载同检。
⑥ 存储挂载双门槛：`StorageMountBinding.kt:22-69`（guest 仅 `/mnt`、`/sdcard`；host canonical 仅 `/storage/emulated/0`）+ `ProotCommandBuilder.kt:239-258` `require` 硬拦。
⑦ 命令注入面干净：`ShellQuote.kt` 单点 `'\''` 实现、`ProotCommandBuilder.kt:169-183` 环境名正则+逐项 Quote、`EmbeddedAdbManager.kt:479-503` 包名/tag 正则 + 关键字本地过滤。
⑧ HostBridge 单一鉴权权威、无签发端点、无 `.bridge-key`（`HostBridge.kt:170-191`；`DistroConfigurator.kt:367` 清理历史 `.bridge-key`）；`host.shell` 默认不下发（`FullAccessGrant.kt:17-70`）。
⑨ 审批矩阵结构化：PLAN 硬拦、`use_capability(call,server=host)` 与直接 host 同矩阵、deferred 与执行器同源（`ApprovalPolicyEngine.kt:41-72,146-189`、`HostCapabilityDirectory.kt:128-140`）。
⑩ 工具输出统一脱敏→再截断/落盘（`ToolExecutor.kt:258-320`、`ExecutionOutputSecrets.kt:15-26`）。

## 最值得先修的 5 条

1. **密钥止损（P0-1）**：删 `keystore.properties`+`tianyan-local.keystore`、口令改环境变量，该密钥签过的对外包按泄露轮换——唯一「不改代码即可止损、后果不可逆」项。
2. **WebChat 防爆破（P0-2）**：PIN 限速锁定+加长、CORS 白名单、token 移出 query、默认 loopback、桥接强制 `ASSISTED`——当前最短的设备接管链。
3. **`ShowerBinderReceiver` 加签名级权限/nonce（P1-3）**：API 29–33 裸奔，截图可伪造即等于模型视觉证据不可信。
4. **明文旅程回归系统守卫 + Provider https 约束（P1-5）**：`base-config=false`、`requireSecureProviderEndpoint()`、拒绝 scheme 降级——小改动直接保护付费凭证。
5. **`resolveSandboxPath` 白名单化 + install-apk 降级为显式确认（P1-6）**：删「原样返回宿主路径」与同名扫描。

> 补充：P1-4（`TianyanBridge` 来源绑定）不依赖任何用户前提，修法只是加一次来源/nonce 校验，建议紧随其后。

## 方法与局限

静态阅读，逐条读到校验点与调用链；未运行 PoC、未上真机抓包（P0-2 爆破速率按「无延迟无锁定、10^6 空间、8 工作线程」估算）。`harness/ProviderClient.kt` 仅 grep 关键片段（`baseUrl`/错误格式化/鉴权头）未整读。修 P1-6 会与 `docs/SECURITY_SURFACE.md` §4 风险 #3 冲突，需同步更新该文档（其第 212 行有维护约定）。
