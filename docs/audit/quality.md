# 天衍 · 质量与健壮性审计（测试覆盖 · 错误处理 · 空态 · i18n · 日志）

范围：全仓 `src/main`（Kotlin）与 `src/main/res/values*`、`.github/workflows/*`；只读审计。
口径：**可验证的失效路径优先**。全部数字为本次 grep/脚本实测，行号取自当前工作区。
基线：main Kotlin **1659** 文件 / test Kotlin **698** 文件 / `@Test` **3888** / `androidTest` 目录 **0**。

---

## P0

**P0-1　CI 只执行 87/698 个测试类（12.5%），发布流水线零测试**
证据：`architecture-baseline.yml` 全仓仅 48 条 `--tests` 白名单、7 个模块；`release.yml:106` 只 `assembleRelease`，无 test；`feature/chat`(28)、`tools`(25)、`feature/settings`(20)、`core/model`(18)、`core/security`(1) 等 14 个模块的测试从未在 CI 跑过。
影响：3888 个用例中约 3100 个永不执行，"测试存在"给了虚假安全感，下面 P0-2 的零测试因此长期无人发现。
改法：CI 增加一次 `./gradlew testDebugUnitTest` 全模块（白名单 job 保留做快反馈）；release 前必须全绿。

**P0-2　鉴权/凭据/供应链/下载四类高风险类零测试（逐类 grep 验证 0 引用）**
零测试：`McpAuthFilter`（MCP 鉴权，常量时间比较，无 null/空/缺 Bearer 用例）、`SafeFileTree`（102 行，防符号链接跟穿）、`OciRegistryClient`(249)、`RootfsInstaller`(436)、`RootfsHttpPolicy`(23，拦 HTTPS→HTTP 降级)、`TarFileSystemOps`(51)、`ProotMountLayout`、`FileDownloader`(224，ResumableFileDownloader 0 引用)、`AppUpdateManager`(185)、`GitCredentialsStore`(85)、`WorkshopKeystore`、`SecretRedactingInterceptor`(70)、`HostShellCommandBuilder`(93)。
`SecretManager` 例外但等价：仅被 Robolectric 以 `@Implements` 打成 mock 桩（`ProviderModelResolverTest.kt:30`），encrypt/decrypt 往返、空串、旧 alias 迁移、GCM 失败返回 null 全无行为测试。
影响：这些是"坏一次就全盘失效"的点位；反而是 `ChecksumVerifier`/`VersionCompare`/`CcSwitchClient` 这类纯函数被单独测了。
改法：先补 6 个纯 JVM 可测项（McpAuthFilter 的真值表、SecretManager 往返、SafeFileTree 软链、FileDownloader 重试/超限、AppUpdateManager 解析、extractHost 表驱动），成本 1~2 天。

**P0-3　配置缺失时静默降级为默认弱口令 `admin/admin123`**
位置：`feature/settings/.../CcSwitchSandbox.kt:24-47`——凭据文件不存在时 shell 里 `printf 'admin'/'admin123'` 落盘；`:44` `catch (_: Exception) { Triple("admin","admin123","") }`；`:49-52` 三处 `ifBlank { ... }` 兜底。
现象：读取失败、超时、文件缺失三条路径**全部**回落到同一对固定口令，无日志、无 UI 提示，且口令被写进沙箱 `~/.cc-switch`（chmod 600 但同用户可读）。
影响：本机任一进程/局域网内可达该 Web 面即可用公开默认口令登录；用户以为"设过密码"。
改法：改用随机生成（`SecureRandom`）并落盘；失败时向上抛"中枢未就绪"而非伪造凭据；至少 `Log.w` + 设置页红字。

**P0-4　失败被伪装成正常数据：首页指标与组件探测静默降级**
位置：`HomeViewModel.kt:224/257` `catch (e: Exception) { emptyList() } / { context.filesDir }`；`SystemResourceMetrics`(`:49-64`) 默认值直接编造 `linuxDistro="Ubuntu 24.04 LTS"`、`engineVersion="proot-distro 5.10.0"`、内存/存储 `0`；`WorkspaceViewModel.kt:174` `catch (_: Exception) {}` 吞掉 `probeInstalledComponents()` 失败。
影响：沙箱未就绪/探测失败时首页仍显示具体数值与发行版名，用户与诊断都拿不到"不可用"信号；工作区把失败渲染成"所有组件未安装"，引导用户重复安装。
改法：指标加 `unknown` 态（`null` 而非 0）与"数据不可用"文案；探测失败显式 `_error`。

---

## P1

**P1-1　220 处 `"…${e.message}"` 直接展示给用户，且无 null 兜底**
实测：含 `.message` 的字符串模板 220 条，其中带 `orEmpty()`/`?:` 的 **0** 条。样例 `ToolDetailViewModel.kt:199` `"网关启动失败：${e.message}"`、`:255`、`:324`、`CcSwitchViewModel.kt:267/492`。
影响：异常无 message 时界面出现"网关启动失败：null"；有 message 时把 SQL/路径/URL/上游响应体直抛给普通用户。
改法：统一 `e.userMessage(fallbackRes)` 扩展（`message.orEmpty().ifBlank { getString(fallback) }`），技术细节只进 AppLogger。

**P1-2　247 处 `catch (Throwable|Exception)` 既不记日志也不重抛**
实测口径：catch 后 400 字符内无 log/logger/throw 的有 247 处；`catch (_: Exception)` 共 106 处。典型：`WorkspaceViewModel.kt:174` 空块、`CcSwitchViewModel.kt:203`/`:463`/`:1023` 空块与 `emptyMap()`/`null`、`HomeViewModel.kt:224`。
影响：失败现场零留存（AppLogger 已具备脱敏与落盘，却在这 247 处被绕过），线上只能靠用户口述复现。
改法：至少 `logger.w()`；纯预期的分支改成具名 `Result` 返回，避免"空 catch"这种无声失败。

**P1-3　257 处 `}.getOrNull()` + 182 处 `}.getOrDefault(...)` 静默吞**
实测：`runCatching` 全仓 1045 处，`onFailure` 只有 259 处。样例 `StorageUsageViewModel.kt:120/153/180`（`inspect()` 失败取上一次值，界面无变化）、`CcSwitchViewModel.kt:463`。
影响：`getOrDefault` 把"读失败"和"读到了空"合并成同一状态，用户看到旧值却以为刷新成功。
改法：区分 `Failure`/空值；刷新失败要在页面上留痕（`StatsScreen.kt:130-152` 已是正确范式：错误态 + 重试按钮）。

**P1-4　i18n key 缺口实测 217 条（不是 59 条），`feature/git` 整模块无英文**
实测（比对 **全部** `res/values/*.xml` 而非只有 `strings.xml`）：zh 2918 / en 2648，缺 **271**，扣除 `assets/plugins`(51) 与 `project-template`(3) 后，应用代码缺 **217**：`feature/git` 71（整个 `values-en` 目录不存在）、`feature/bot` 68、`feature/chat` 59、`feature/a2uipoc` 10、`harness` 7、`feature/workspace` 2；另有 1 个 en 独有键。
对上游"zh 589 / en 531 缺 59"的复核：该数字只等于 `feature/chat/src/main/res/values/strings.xml` 这一份（我实测 chat 738/680，缺 59 一致），**作为全仓结论偏小 3.7 倍**。
改法：先补 `feature/git`（整模块 0 英文，英文用户进 Git 页全中文）与 `bot`/`chat`。

**P1-5　653 处静态中文 `Text("…")` 不在本地化映射表内，英文 locale 下原样显示中文**
机制：`SettingsLocalization.kt` 有 507 条 `"中文" -> R.string.settings_legacy_xxxx` 映射 + `LocalizedText`，39 个文件用 `import ...LocalizedText as Text` 别名接入；`DeveloperLocalization.kt` 另有 114 条。
实测：`Text("中文")` 共 **1016** 处，命中映射 **234** 处，未命中 **782**（静态 653 + 含模板变量 129）。分布：settings 321、workflow 149、chat 49、developer 46、bot 42、workspace 16。
影响：设置页本地化只覆盖约 43%，工作流编辑器整屏中文；映射式改造属于"半截迁移"，每加一句文案就要同时改两个文件。
改法：停止扩张映射表，新代码直接 `stringResource`；对 149 处 workflow 先批量抽键。

**P1-6　58 处中文 Toast/Snackbar 完全绕过 strings 体系**
样例：`ModelExportDialog.kt:54` "导出文件保存成功"、`:98` "已复制 JSON 配置到剪贴板"、`ToolCenterScreen.kt:674/735` "日志已复制到剪贴板"、`SystemDevSettingsScreen.kt:140/297/315` 权限引导。
影响：这类文案不经 `LocalizedText` 映射，英文 locale 下 100% 中文，且无法通过改 XML 修。
改法：抽 `R.string`；可加 lint/自定义检查禁 `Toast.makeText(..., "…中文…")`。

**P1-7　列表空态缺失：55 个 Lazy 屏中 16 个无任何空分支**
坐实样例：`SnapshotSheet.kt:50-54`（`interactiveRefs` 为空 → 只剩标题和"关闭"，正文空白）、`PluginManagerScreen.kt:48-58`（无插件时只有错误行与一句说明）、`ModelEditorCandidateList.kt:18`（`items(models)` 无判空）。
影响：空数据 = 白屏，用户无法区分"加载完了但没内容"和"坏了"。
改法：统一 `RuntimeEmptyState(icon,title,hint,action)`；`StatsScreen.kt:153-165` 的空态文案可直接作为范式。

**P1-8　主链路失败态没有重试入口**
位置：`ChatErrorBanner.kt:20-44` 只有一个"关闭"（`ChatScreen.kt:776` 是唯一调用点）；对照 `StatsScreen.kt:130-152`（错误 + "重试"按钮）是全仓正确范式。
影响：模型请求/网络失败后用户只能自己重发；`ChatErrorBanner` 关闭即丢失错误原文。
改法：banner 增加"重试上一条"（`ChatViewModel.regenerateLast()` 已存在，接线即可）。

**P1-9　CancellationException 处理不一致：37 个 suspend 文件 0 处理 vs 全仓 444 处显式重抛**
实测：含 `suspend`、`catch/runCatching` ≥5 处、且文件内无 `CancellationException` 的有 37 个；重灾区 `GitManager.kt`(29)、`BrowserViewModel.kt`(23)、`WorkspaceManager.kt`(21)、`RedTeamCoordinator.kt`(20)、`PrivilegeManager.kt`(17)。
影响：页面退出/切换导致协程取消时，被捕获成"操作失败"并弹错误提示——离开页面后收到失败 Toast。
改法：统一 `runCatchingCancellable {}`（`onFailure { if (it is CancellationException) throw it }`），`StorageUsageViewModel.kt:120` 已是正确写法。

**P1-10　`AppUpdateManager.downloadApk` 无大小上限、无摘要校验、无 HTTPS 强制**
位置：`core/network/.../AppUpdateManager.kt:127-167`——`while(true) input.read()` 直写 `cacheDir/updates`，`downloadUrl` 不做 scheme 校验、不比对 `apkSizeBytes`、无 SHA-256；同类能力在 `FileDownloader.kt:26-100` 已实现（HTTPS 强制 + `maxBytes` 1 GiB + sha256 + 3 次重试）。
影响：双实现分歧——加固只在新下载器生效；更新包被替换/截断只会"安装失败"而不会提前拦截，恶意/错误 URL 可打满磁盘。
改法：直接复用 `ResumableFileDownloader`（`core/network` 内已有依赖），或至少补 `maxBytes` + `sha256`。

---

## P2

**P2-1　`GitCredentialsStore.extractHost` 对带 userinfo 的 URL 解析错误**
位置：`GitCredentialsStore.kt:74-84`，顺序为 `substringBefore('/') → substringBefore(':') → substringBefore('@')`；`https://user:token@github.com/x/y.git` 得到 **`user`** 而非 `github.com`。
影响：URL 内嵌凭据的仓库取不到已存 Token，push 报"未配置凭据"，用户重存也无效（存的是 `github.com` 键）。该类 0 测试。
改法：先剥 userinfo（`substringAfterLast('@')`）再取 host；补表驱动测试（http/https/ssh/git@/含端口/含 userinfo）。

**P2-2　`chat_nerd_output` 漏传格式化参数，UI 显示字面量 `%1$s`**
位置：`ChatMessageNerdLine.kt:132` `stringResource(R.string.chat_nerd_output)`，资源为 `输出 %1$s tok`（`:80` 的另一处调用是正确的，传了 `formatTokenCount`）。Compose 无参重载不执行格式化，故不崩溃、直接显示 "%1$s"。
影响：token 用量图例出现"输出 %1$s tok 1234"。全仓此类漏参仅此 1 处（已全量比对 2918 个 key 的参数个数）。
改法：补参数或改用 `chat_nerd_output_label`。

**P2-3　release 不剥离日志，54 个文件用裸 `Log` 绕过 AppLogger**
证据：`app/proguard-rules.pro` 无 `-assumenosideeffects class android.util.Log`；全仓 `android.util.Log` 54 文件 304 处，`AppLogger` 仅 65 文件覆盖。裸 Log 无脱敏、不落盘。
正面：抽查裸 Log 无明文 Token/API Key/请求体（最敏感的是 `ShowerController.kt:481` 的 keyCode），`AppLogger` 走 `SecretRedactor` 且按 1 MiB/5 MiB 轮转，`agent_debug.log` 受用户开关门控且只记元数据（`AgentEventLogger.kt:17-25`）。
改法：proguard 加 Log 剥离（保留 `Log.e`）；`Log.*` 纳入 import 黑名单（`architecture-policy.json` 的 `importBans` 现成机制）。

**P2-4　日志写公共 `Download/Tianyan` 目录，脱敏是正则**
位置：`AppLogger.kt:176` `PUBLIC_LOG_DIR = "Tianyan"`，失败才回落私有目录。脱敏依赖 `SecretRedactor` 的关键字/前缀正则，未命中即原文外泄；该目录其他应用可读（Android 10+ 需 SAF，但用户导出/分享场景会扩散）。
改法：默认写私有目录，导出时再复制；对 `agent_debug.log` 默认关闭。

**P2-5　`release-smoke` 用 `-x lint` 跳静态检查，且无覆盖率门槛**
证据：`release-smoke.yml:63` `assembleRelease ... -x lint`；全仓无 kover/jacoco/detekt/ktlint 配置，无覆盖率红线。正面：`architectureCheck`（maxFileLines 400、import 黑名单、无环依赖）确实在 CI 跑。
影响：Lint 能抓的 `UnusedVariable`(上面 `catch (e: Exception) { emptyList() }` 的 e)、`StringFormatInvalid`、`MissingTranslation` 全部放行。
改法：release 前跑 `lintRelease`（`abortOnError`）；接 kover 设"新增代码覆盖率"门槛。

**P2-6　其余结构性小缺口**
① `DistributionCatalog.require` 未知 distro 静默回落 `supported.first()`（Ubuntu），11 处调用 → 非 Ubuntu 环境显示"Ubuntu"；② 骨架屏只有 2 个模块（`WorkspaceSkeleton`、`TianyanA2uiSkeleton`）+ `ChatMessageList`，其余列表加载只有 spinner 或无提示；③ 输入框无长度上限（附件有 `maxBytes`，文本没有），超长粘贴直入库/直送模型；④ 0 个 `androidTest`：Compose/Room 的真机行为零回归。

---

## 最值得先修的 5 条

1. **把全量单元测试接进 CI**（`./gradlew testDebugUnitTest` + release 前必跑）——P0-1 是所有其他问题的放大器。
2. **cc-switch 默认口令 `admin/admin123` 改为随机生成 + 失败显式报错**——P0-3，唯一"默认不安全"的降级路径。
3. **补 6 个纯 JVM 高风险零测试**（McpAuthFilter 真值表、SecretManager 往返、SafeFileTree 软链、FileDownloader 超限/重试、AppUpdateManager 解析、extractHost 表驱动）——P0-2 + P2-1，一天内可落地。
4. **`e.message` 统一走 `userMessage()` + 247 处空 catch 至少落日志**——P1-1/P1-2，一次性收口"失败不可见"。
5. **修首页/工作区的假数据降级 + 补 217 条 i18n 缺键（先 `feature/git` 整模块）**——P0-4/P1-4，前者误导诊断，后者让英文 locale 直接不可用。

---

### 复核命令（可重现本轮数字）

```bash
# 测试类总量 / 被测类总量
find . -path "*/src/test/*" -name "*.kt" | grep -v "^./build" | wc -l      # 698
grep -rn "@Test" --include=*.kt */src/test */*/src/test | wc -l             # 3888
# CI 实际执行的测试类数量（48 条 --tests 白名单 → 87 个类）
grep -o "\-\-tests '[^']*'" .github/workflows/architecture-baseline.yml | wc -l
# i18n 缺键（需要用全部 values/*.xml，只比 strings.xml 会漏 18 个文件/模块）
# 见本报告 P1-4 的统计口径说明
# 中文 Text() 命中率
grep -rn 'Text("' --include=*.kt feature | grep -P '[\x{4e00}-\x{9fff}]' | wc -l  # 1016（全仓口径）
```
