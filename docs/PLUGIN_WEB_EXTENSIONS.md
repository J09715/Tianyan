# 静态 Web 页面、外部包与能力授权（P5）

`plugin:ui` 提供 `WebPagePlugin`，将宿主注册的静态 Web 页面接入既有 PAGE 目录。`plugin:runtime` 的 `PluginCapabilitySession` 管理当前文档的授权与请求。注册只构造描述，不加载 WebView、静态文件或执行器。

`.txplugin` v2 现支持 PROCESS 与受限静态 WEB 两种包。编译时接口与外部静态包共用 Web 宿主，不加载远程页面或外部原生代码；受管理服务资源目前仅支持宿主注册的有界静态快照。先前的原生页面与 Harness 扩展规则不变。

## 使用与验证入口

设置 → 应用功能插件 → Web 插件示例 → 打开。点击“读取应用信息”后，在应用原生弹窗中选择单次允许、当前页面 5 分钟允许或拒绝。授权后只返回应用包名、Android API 与插件 API 版本；顶部可撤销。关闭页面再打开需要重新确认。

入口工厂：[`WebPluginDemo`](../plugin/ui/src/main/java/top/tianyan/app/plugin/ui/web/WebPluginDemo.kt)。工厂由 `NativePluginPlatform` 装配，页面停用后遵循 P2 的入口和代次规则。

## 编译时接入

在宿主装配代码中创建 `WebPagePlugin` 并调用 `create()`，把返回的 `NativePagePlugin` 加入现有目录：

```kotlin
WebPagePlugin(
    id = "example.info",
    version = "1.0.0",
    title = { "信息面板" },
    description = { "读取应用公开信息" },
    capabilities = listOf(WebPluginCapability(
        PluginCapability("host.app-info", "application"),
        title = { "允许读取应用信息？" },
        description = { "仅返回应用包名。" },
    ) { context, _, _ -> context.packageName }),
    loadAssets = { context ->
        listOf("index.html", "plugin.js").associateWith { name ->
            context.assets.open("info-panel/$name").use { it.readBytes() }
        }
    },
).create()
```

`loadAssets` 与执行器都是受信任的宿主代码。实际权限由执行器负责，不得登记绕过现有工具审批、PLAN 或 Android 系统权限的实现。执行器在页面协程中调用；阻塞操作须切换 dispatcher 并协作取消。执行器异常只返回固定错误码；返回文字不自动脱敏，含敏感数据的实现须在返回前按所属业务边界脱敏。

资源必须包含 `index.html`，最多 64 项、合计 2 MiB，路径只能由普通文件名和目录组成；不接受路径穿越、编码路径、查询串或 URL。宿主保留 `theme.css`，提供当前 MaterialTheme 颜色变量 `--surface`、`--on-surface`、`--primary`、`--on-primary`、`--container`，供页面样式消费。主题变量是页面创建时的快照。

HTML 使用外部脚本和样式，不使用内联脚本、iframe 或 Worker。当前 CSP 不允许网络连接、表单提交和内联脚本。每个页面使用独立随机 HTTPS 来源；所有资源请求由静态快照响应，不回退真实网络。

## 消息协议

仅在支持 `WEB_MESSAGE_LISTENER` 的 Android System WebView 上启用，缺失时显示更新提示，不降级为无来源校验的桥。协议使用 AndroidX WebKit 的 [addWebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener))。

主文档使用 `window.tianyanPlugin.postMessage()` 提交 JSON 字符串：

```json
{"id":"request-1","action":"host.app-info","target":"application"}
```

监听 `window.tianyanPlugin.onmessage` 接收：

```json
{"id":"request-1","success":true,"text":"result"}
```

消息最多 4096 字符。读取操作只接受上述三个字符串字段；剪贴板写入额外要求 `text` 字符串。禁止嵌套结构、重复/未知字段和授权/身份/执行环境字段。来源与主帧标记取自 WebView 回调，不取自消息。返回文本最多 32768 字符；固定失败码包括 `origin_denied`、`capability_denied`、`replayed_request`、`busy`、`permission_denied`、`execution_timeout`、`execution_failed`、`revoked`。

同一页面最多受理 256 个请求 ID，每个 ID 只能受理一次，包括拒绝、失败和取消的请求；超限需关闭再打开。每页最多一个在途请求，避免排队或同时弹出任意数量的授权窗。授权等待最多 60 秒，操作执行默认最多 10 秒，均为协作式超时。

## 授权与撤销

身份由宿主固定为插件 ID、版本、运行代次、随机文档来源与 `android:local` 环境；动作和目标必须精确匹配登记的能力。只读操作不接受输入；剪贴板写入只接受有界纯文本。不开放文件通配符、任意参数、Shell、凭证或全权能力。

原生授权弹窗携带随机令牌；旧弹窗令牌不能批准后来的请求。单次授权只执行被冻结的当前请求；写能力（`clipboard-write` 与 `install-apk`）始终逐次确认，不接受持续授权。只读能力的持续授权限定同一文档、同一动作与目标，按单调时钟在写能力 5 分钟上界之外可放宽到最长 30 分钟（`PluginCapabilitySession.longLivedGrantMillis`，上界 1 小时），仍不落盘。新页面、新版本、新代次、进程重启没有旧授权。插件停止时同步关闭代次准入，尚未销毁的页面也不能新发起调用或批准旧弹窗。

撤销清空授权、关闭待确认窗口并取消在途请求；页面关闭永久作废该文档会话并销毁其专用 WebView。执行器不配合取消时，不保证按时停止；撤销不能回滚已提交的副作用，也不保证底层动作 exactly-once。这里提供应用层代理门控，不是同 uid 恶意代码隔离。

共享 HostBridge 的旧 key 已迁移（见 [`PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md) 第 26 节）：插件调用与沙箱写端点共用 `HostCredentialAuthority`，但进程插件、Agent 与浏览器 MCP 不使用此文档会话；本片不改变这些路径的权限。受管理本地服务、只读长期授权与开发者全权模式的收尾见文末「Phase C 收尾」节。

## 本地验证

```powershell
$env:JAVA_HOME="D:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :plugin:runtime:test :plugin:ui:testDebugUnitTest :feature:navigation:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug architectureCheck --continue --console=plain
```

测试覆盖来源/目标越权、主帧限制、单次/限时授权、版本/代次/环境隔离、旧令牌、请求重放、并发和容量、撤销取消、超时、错误信息、有界静态资源及实际导航装配。Robolectric 资源测试不运行 Chromium；WebView 消息回调、CSP、真机页面与渲染进程退出仍需设备验收。测试不调用模型 API。

2026-10-18 上述专项命令通过，新增 25 项测试通过；Debug APK 和架构检查通过。另尝试全库 `test`，因既有 DataStore API 29 测试下载 Robolectric 镜像阻塞而中止，尚无本轮全库通过结论。示例 JS 的中英文请求、去重、成功/拒绝处理已通过本地模拟消息通道冒烟检查。

## 外部静态包（2026-10-19）

设置 → 应用功能插件 → 外部 Web 插件 → 导入，从系统文件选择器选择 `.txplugin`。原生确认窗固定展示该次预览的身份、版本和能力请求；完整导入再次核对预览清单与全部资源。导入不自动打开页面、不持久授予能力，也不运行 Linux 安装步骤。系统文档提供者可能需要从云端下载文件，这不意味着插件页面可以联网。

用下面的本地命令生成可导入示例，不下载资源或调用模型：

```powershell
.\tools\package-web-example.ps1
# 输出 build/examples/web-info.txplugin
```

生成器复用内置示例的三个静态文件并生成真实 SHA-256，插件身份是 `example.web-info`，不使用宿主保留的 `tianyan.*`。默认示例仅声明读取应用基本信息，导入后点击“打开”，再在原生弹窗中确认，便可验证完整链路。宿主生成的 `theme.css` 使用独立、最多 4 KiB 的配额，不占包的 2 MiB payload 额度。

最小清单（`integrity` 的示例哈希必须替换为文件真实值）：

```json
{
  "schemaVersion": 2,
  "id": "web-info",
  "name": "Web Info",
  "description": "Offline static information page",
  "version": "1.0.0",
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE",
  "plugin": {
    "id": "example.web-info",
    "api": {"minimum": 1, "maximum": 1},
    "execution": "WEB",
    "extensions": [{"id": "main", "point": "PAGE"}],
    "capabilities": [{"name": "APP_INFO", "target": "application"}],
    "integrity": {"index.html": "<64 位小写 SHA-256>"}
  }
}
```

包根目录为 `manifest.json` 与 `payload/`，固定入口 `payload/index.html`。WEB 最多 63 个文件、资源总计 2 MiB，为宿主生成的 `theme.css` 另留一个文件槽；路径最多 16 段和 256 字符。ZIP 条目最多 129 个、解压合计最多 3 MiB，清单单独最多 1 MiB。所有 payload 普通文件都必须列入 `integrity`；脚本是 Web 页面的静态 JS，不是 Shell/原生安装代码。其它可执行入口、网络或文件能力、依赖、资源声明均拒绝，详见 [v2 包规范](PLUGIN_PACKAGE_V2.md)。

工具与 Web 包共用 `ToolRegistry` 的身份校验及不可覆盖版本目录；对外工具清单过滤 WEB，不能把它传入进程安装收据。`WebPluginRepository` 的当前包目录属于应用本身，不随 Linux 发行版切换，导入前不要求 Linux 就绪；授权环境固定为 `android:local`。

打开路由只保存非敏感的包 ID、插件 ID 和版本；当前版本不匹配时显示不可用，不默默打开新版或旧版。每次打开都校验存储清单、普通文件集合及内容哈希，再创建独立的页面生命周期和随机来源。导入升级或移除期间关闭该包新调用准入，提交新版本或移除后旧页面退出，授权不继承。失败升级保留原版本；同版本不覆盖，降级或仅变更 build metadata 拒绝。没有版本回切 UI。

移除操作绑定确认时的清单；版本改变后旧确认不能删除新包。删除该包全部静态版本目录，不跟随符号链接、不清理全局路径。文件系统错误可能导致部分删除，失败不报告成功；不承诺任意磁盘故障恢复。文件或指针损坏时拒绝打开，不猜测替代版本。完整性校验证明文件与清单一致，不证明发布者身份，也不构成同 uid 恶意代码隔离。

验证命令：

```powershell
.\gradlew.bat :plugin:packages:test :tools:testDebugUnitTest :plugin:ui:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:navigation:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug architectureCheck --continue --console=plain
```

2026-10-19 验收：新增 19 项测试通过（策略 5、包仓储 12、路由 1、主题配额 1）。最终全库 `test :app:assembleDebug architectureCheck --continue` 通过，含应用 DI 图与旧 PROCESS 回归；示例归档文件集合和全部哈希独立复核通过。上轮缺失的 API 29 测试镜像已下载并按官方 SHA-512 校验；本轮全库测试已完成。尚未在真机 WebView/Chromium 上验证 SAF、来源回调和页面交互。

## 独立宿主凭据（2026-10-19）

外部包 APP_INFO 现由原生执行器将文档身份交给 `HostBridge.readPluginAppInfo`，与 `POST /api/plugin/call` 共用 `PluginHostGateway`。原生确认是签发前提，签发没有 HTTP 接口。凭据是宿主内存中的随机值，最多存活 10 秒且只能消费一次；失败请求也消费它，不能重放、续期或变更目标。凭据表由 `HostCredentialAuthority` 统一持有，全实例上限 256（插件票据与沙箱凭据共用该额度），超限拒绝签发；签发时清理过期记录。

凭据不返回 JS、不写入包/磁盘/日志，不使用 `.bridge-key`。请求绑定插件、版本、代次、页面来源、环境、动作和目标；旧共享密钥与该入口无关。当前插件侧结构化动作仍是 `host.app-info/application`，没有参数命令、文件路径或任意执行器入口；内置示例继续使用编译时只读执行器。

沙箱侧（P5 收尾片）已迁移到同一权威：`SandboxCredentialStore` 按发行版签发一枚 8 小时、可复用、可撤销的凭据，经 `ProotCommandBuilder` 在 Provider 环境之后叠加注入 `TIANYAN_BRIDGE_TOKEN` / `TIANYAN_BRIDGE_ACTIONS`；`/api/install-apk`、`/api/shell`、`/api/logcat` 分别要求 `host.install-apk`、`host.shell`、`host.logcat` 绑定动作，`HostBridge` 不再有构造期共享密钥，也不再有 `.bridge-key` 文件与免动作的 Bearer 比较。写入端点不再接受沙箱传入的自由端口。凭据可被同 uid 的沙箱进程读取，故仍是应用层代理门控而非强隔离。

撤销取消原调用 Job，升级/移除关闭包准入；执行前后核验调用存活、包仍可用及凭据期限。停止 HostBridge 推进凭据代次、作废全部凭据（含沙箱凭据）与正在返回的旧代结果；页面自身授权仍按原文档会话管理。撤销不能回滚已完成副作用。本片未引入网络或同 UID 强隔离；`host.shell` 现默认随沙箱凭据移除，仅当持久开发者模式开启时经 `FullAccessGrant` 升权下发（应用层门控，非强隔离边界），见文末「Phase C 收尾」节。

新增离线测试覆盖全部身份维度、动作/目标变更、过期、单次消费、并发重放、撤销/停止、容量、错误脱敏及真实 loopback HTTP 的中文 UTF-8 请求；请求解析另覆盖旧入口兼容、截断/重复/负长度/分块头和容量拒绝。无需 API Key：

```powershell
.\gradlew.bat :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.bridge.*" :plugin:ui:testDebugUnitTest :feature:settings:compileDebugKotlin architectureCheck --console=plain
```

本片验收：新增 14 项离线测试通过（宿主代理 11、HTTP 分帧 3），全库 `test :app:assembleDebug architectureCheck --continue` 通过（979 tasks，36 模块架构门禁），架构基线工具 9 项测试通过。未调用真实模型 API，未做真机 WebView 验收。

## 逐次确认的剪贴板写入（2026-10-19）

外部 WEB 清单可显式声明 `{"name":"CLIPBOARD_WRITE","target":"clipboard"}`，映射到固定动作 `host.clipboard-write`。没有 `CLIPBOARD_READ`，也不读取/缓存原剪贴板。导入只是声明能力，不授予权限。

```json
{"id":"copy-1","action":"host.clipboard-write","target":"clipboard","text":"Hello from Tianyan"}
```

`text` 必须是非空字符串，最多 1024 个 UTF-16 代码单元，拒绝 NUL；消息总长仍限 4096 字符，宿主 HTTP 包含身份的完整请求体限 4 KiB。读取动作不能附带 `text`。提交内容固定在当前请求及原生弹窗中；页面后续编辑或新消息不能替换已显示的内容。每次只提供“仅允许一次”和“拒绝”，原生授权层即使收到持续授权选择也拒绝执行。

原生确认明确展示将替换剪贴板的文本，控制字符和方向控制符以转义形式显示，避免改变确认窗的阅读顺序；实际复制值保持原样。宿主短期凭据同时绑定完整文本、动作、目标和文档身份；替换文本、重放或调用读取接口均拒绝。成功仅返回 `{"copied":true}`，不回传文本或原剪贴板内容。实际写入使用 Android `ClipData.newPlainText`，不授予 URI/Intent 能力，设置敏感预览标记；该标记不阻止其它应用读取。覆盖剪贴板后不可回滚，取消与撤销也不恢复旧内容。

生成专用离线示例：

```powershell
.\tools\package-web-example.ps1 -Clipboard
# build/examples/web-clipboard.txplugin
```

示例不调用浏览器 Clipboard API；通过受限消息通道申请原生确认。中英文界面固定提交快照、禁用重复点击、关联回复 ID，并处理拒绝与超时。示例身份 `example.web-clipboard`，不会覆盖原 `example.web-info` 包。

本片验收：新增 15 项测试通过（逐次授权与预览 5、消息输入 3、包策略 1、宿主文本绑定 4、Android 剪贴板 2）。全库 `test :app:assembleDebug architectureCheck --continue` 通过，含 36 模块架构门禁。中英文示例 JS 的快照、重复点击、回复关联、拒绝和超时本地模拟通过；两个示例包的文件集合及全部 SHA-256 独立复核通过。未调用真实模型 API，未在真机 WebView 验收。

## 受管理服务资源首片（2026-10-19）

设置 → 应用功能插件 → 受管理 Web 服务示例 → 打开。注册和开启插件不会启动服务，只有实际打开页面才创建独立服务实例。示例在宿主进程启动仅监听 `127.0.0.1` 的资源服务，使用系统分配端口、随机实例路径和内存认证头，提供内置示例的三个文件；不启动 Linux、安装工具或注入 Provider 环境。

`ManagedWebAssets` 只接受原生注册的端口、资源路径、预期 SHA-256 和服务存活/回收回调，不接受来自 JS 或外部包的 URL、凭据或命令。最多 63 个静态文件、合计 2 MiB，固定包含 `index.html`，路径和哈希有界。只连接数字地址 `127.0.0.1`，不使用系统代理，不跟随重定向、不续传认证、不接受压缩响应、不保存 Cookie；每个文件只接受 HTTP 200，流式累积同样检查总量。单次快照最多 10 秒，单个请求最多 3 秒，取消会取消在途 HTTP 请求。收到的全部内容必须逐文件匹配宿主注册哈希，防止误把另一端口响应当成目标内容；这不是本机恶意进程隔离或强进程身份认证。

验证后通过 `WebPluginAssetLease` 交给现有 WebView：页面仍使用独立随机 HTTPS 来源，凭据和服务 URL 不进入页面，资源请求仍不回退网络，CSP 不放开 fetch、WebSocket、iframe 或 Worker。它支持服务提供的静态资源快照，不支持任意动态站点、后端请求或现有工具服务的直接页面加载。外部 `.txplugin` 后续新增的固定 PRoot 来源见文末；任意启动命令仍不接受。

资源取得前先在插件代次中预留清理记录，页面退出、来源加载失败或打开期间取消都归还本页面实例；成功回收后移出账本，失败记录保留供停用/关闭重试。尚在取得中的资源不能被误判为已经释放，进入排空后晚返回的服务也会关闭。工厂必须在返回租约前的取消/异常路径自行清理部分取得的资源，释放回调必须幂等且只有确认成功才返回 true。

服务存活由原生租约报告，退出后调用准入立即拒绝；页面每 250 ms 检查来源并关闭授权与视图。该轮询受协程调度影响，不是实时保证。已取得资源也不会借用重启后的服务实例；重新打开产生新服务、来源和授权。页面关闭只回收该页面启动的实例，不停止工具中心已有服务。

验证命令（无 API Key）：

```powershell
.\gradlew.bat :plugin:runtime:test :plugin:ui:testDebugUnitTest :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.ManagedWebAssetsTest" :feature:navigation:testDebugUnitTest architectureCheck --continue --console=plain
```

本片验收：新增 18 项测试通过（loopback 读取/边界/取消 9，来源租约 4，取得/取消/排空清理 3，代次资源提前释放与重试 2）；真实导航装配已验证新增入口惰性注册。全库 `test :app:assembleDebug architectureCheck --continue` 通过，含 36 模块架构门禁和应用 DI 图。文档本地链接与改动格式检查通过。未调用真实模型 API，尚未在真机 WebView/Chromium 验收。宿主接入必须通过有有效 PluginActivation 的 PluginRuntime/PAGE 宿主，不支持绕过生命周期直接呈现 WebPluginPage。

## PRoot 进程静态来源（2026-10-19）

设置 → 应用功能插件 → PRoot Web 服务示例 → 打开。需要用户已选择本地系统、Linux 就绪且安装了 `/usr/bin/python3`；不自动初始化 Linux、安装 Python、调用模型或复用工具中心的共享服务。注册、启用或呈现管理列表不分配进程。原生页面显示前提说明，缺失依赖或启动失败时显示固定资源不可用提示。

该入口由宿主注册 `ProotWebAssetService`，先返回准备态资源租约并登记清理，`load` 阶段才调用 `LinuxRuntime.startBackground`。固定程序使用 Python 标准库，`env -i` 清空 guest 环境，Python `-I -S` 禁用用户路径与 site 初始化；资源与随机认证信息通过有界初始管道写入传递，不进入文件、环境变量、命令参数或日志。不接收外部包脚本或动态启动命令，也不注入 Provider 凭证。

启动配置编码后（含换行）最多 4 KiB，在分配进程前检查。该限制面向当前小型宿主示例；不能据此加载任意大型 Web 站点。资源本身仍限 63 文件、合计 2 MiB，文件集合由宿主指定，全部内容经原生 SHA-256 检查。Python 仅监听数字 loopback 地址，操作系统分配端口，随机实例路径及认证头；禁用请求日志，最多并发处理 16 个连接，每个连接 2 秒读超时。启动标记只包含实例 ID 和实际端口，不含凭据，宿主按原实例匹配，并支持标记跨输出块。

打开时固定发行版，并给进程分配独立 `plugin-web:<UUID>` ID。等待就绪及返回资源全程最多 15 秒；目标系统切换、Linux 状态改变或进程死亡会关闭来源准入，页面按原来源监测规则失效。返回字节后，WebView 仍使用独立 HTTPS 来源，不获得 loopback 地址或认证头，宿主动作仍属于 `android:local`，不因此授予 guest 命令能力。

`stopBackgroundInstance` 将实际 `ManagedProcess` 句柄传到注册表，在锁内验证仍是同一实例后回收；排队的旧停止请求不能停止同名替代实例，复制/伪造句柄也不会取得停止权限。接口默认不支持此能力，来源在不支持的后端上拒绝分配进程。关闭或取消不按当前 UI 发行版重选目标；确认失败保留原句柄供重试，不停止其它工具或进程。

这是宿主固定程序的 PRoot 静态来源；外部 WEB 的固定来源选择见下节。现有任意工具服务页面与动态 API 见后续节，WebSocket 不开放，开发者全权模式见文末「Phase C 收尾」节。`env -i` 只控制初始环境，不改变 PRoot 的 App UID、存储挂载及旧共享 HostBridge key 可读性，不提供恶意同 UID 代码隔离。

本片验收：新增 14 项测试通过（PRoot 来源 9、精确实例停止 3、惰性加载 2），覆盖固定发行版、远端/未就绪/不支持后端拒绝、凭据仅走管道、标记跨块、超时/缺依赖、分配后取消、启动报错后从注册表取回原句柄、停止失败重试及同名替代实例保护。最终全库 `test :app:assembleDebug architectureCheck --continue` 通过，含 36 模块架构门禁、应用 DI 图和导航惰性注册。本机 Python 实际执行验证三个资源、认证拒绝、日志无凭据及超长配置拒绝；示例启动包为 3353 字节。文档链接和改动格式检查通过。未调用真实模型 API；本机 Python 冒烟不等于真机 PRoot/WebView 验收，后者仍待执行。

## 外部 WEB 的固定 PRoot 来源（2026-10-19）

WEB 包可在 `plugin` 中声明 `"webSource":"PROOT_STATIC"`，省略时保持 `PACKAGE` 直接资源模式。仅这两个值有效；PROCESS 拒绝非默认来源。声明只选择宿主内置的固定 Python 程序，不允许端口、URL、命令、环境或 Python 回调，不增加页面权限或放开动态连接。

```powershell
.\tools\package-web-example.ps1 -Proot
# build/examples/web-info-proot.txplugin
.\tools\package-web-example.ps1 -Clipboard -Proot
# build/examples/web-clipboard-proot.txplugin
```

示例分别使用 `example.web-info-proot`、`example.web-clipboard-proot`，可与直接资源包并存。导入确认展示 PRoot 前提，列表带来源标记；导入不运行服务、不安装依赖、不授予应用能力。实际打开才重验该选中版本的清单和所有字节，创建准备态租约，在生命周期账本登记后启动独立实例。来源声明同样绑定预览确认，不允许同版本更改，升级需递增版本。

需要已就绪的本地发行版与 `/usr/bin/python3`，不能在远程后端启用。配置由定长身份/认证头和独立压缩的资源组成，总计仍限 4 KiB；预览完整验证归档并检查此容量，导入提交前再次检查，超限直接拒绝并显示专门提示。本模式面向小型页面；较早版本已导入的超限包仍会在打开时被拒绝。两份示例已实际验证该上限，但不能推导为任意 2 MiB 包都能启动。WebView 接收经原生哈希验证的快照，不得到服务端口或凭据。页面 APP_INFO/剪贴板授权环境仍为 `android:local`。

包身份、选中版本、固定发行版与实际进程句柄分别核验。升级/移除关闭旧包准入，来源失效后关闭授权与视图并回收原实例；缺失依赖、启动失败或页面取消也走原清理路径，失败保留句柄供重试。固定程序的进程归属记录为实际插件 ID，不借用内置示例 ID。WEB 不进入 PROCESS 配方或安装收据，不支持任意已安装工具服务。旧共享 HostBridge key 与同 UID 边界保持原有约束。

本片验收：新增 7 项测试通过（来源协议与兼容 3、预览/升级/移除及完整性 2、外部进程归属/失败清理及准入失效 2）。全库 `test :app:assembleDebug architectureCheck --continue` 通过（979 tasks、36 模块架构门禁），日志 `build/p5-external-proot-full-regression.log`。两份示例的文件集合和 SHA-256 独立复核通过；本机实际执行固定 Python 服务，验证资源与认证拒绝、日志无凭据；剪贴板示例 1000 组随机实例配置均未超出 4 KiB。未调用真实模型 API，未提交或安装到设备；真机 PRoot/WebView 验收仍待执行。

## PRoot 包启动容量预检（2026-10-19）

PRoot 包在预览时完整读取有界归档，复核文件集合、SHA-256 与启动容量；导入提交前复核，失败不激活新版本。临时目录在成功、校验失败和取消时均清理，二次读取的清单必须与首读相同。普通 PACKAGE 页面不增加此限制。UI 只映射专用容量错误到固定中英文提示，不展示异常正文或包内文本。

`ProotWebBootstrap` 供预检和启动共同使用：一行最多 4096 ASCII 字节，格式为实例 UUID、制表符、两个 UUID 组成的认证随机值、制表符、资源 JSON 的 zlib/Base64、换行。随机值不参与资源压缩，路径排序固定，因此容量与身份随机值和归档顺序无关。Python 从实例 ID 推导资源前缀，从随机值构造 Bearer 认证；该内部管道格式不提供给包或页面，也不是外部服务协议。启动前继续保留容量检查。

本片验收：新增 10 项测试通过（编码与容量边界 4，超限预览/导入、失败升级保留、普通来源兼容、哈希错误清理、取消清理、源清单变化 6）。全库 `test :app:assembleDebug architectureCheck --continue` 通过（979 tasks、36 模块架构门禁），日志 `build/p5-proot-preflight-full-regression.log`。本机固定 Python 服务验证两份示例、认证拒绝与日志无凭据；新启动包分别为 3271/3959 字节，异常头、Base64、超长配置和解压上限均只返回固定失败。未调用真实模型 API，未提交，未做真机 PRoot/WebView 验收。

## 已安装工具服务作为页面来源（2026-10-19）

工具中心里已运行的本地 Web 服务工具（`ToolManifest` 声明 `isWebService`）可在其详情页打开只读页面：设置 → 工具中心 → 目标工具 → 打开 Web 页面。页面只在后端为本机且服务已在运行时创建，源不启动、不停止、不重启该服务；关闭页面只回收本次来源。远程后端（已选远端服务器）在分配前直接拒绝，不尝试本地回退。

端口与资源路径只来自原生清单，经 `ToolManager.serviceSpec(toolId)` 取得；页面不接受来自 JS、外部包或偏好设置的地址、凭据或命令。`ToolServiceDescriptor` 只承载原生字段（端口、基础路径、所有者插件 ID、固定发行版），空值或越界在构造时拒绝。

该来源的资源集在创建时未知，因此采用 `IndexedManagedWebAssets`：按路径有界、阻塞读取，**首次响应即钉住该路径的 SHA-256**；同一路径后续字节变化、路径数超过 64 或合计超过 2 MiB 时整条来源立即关闭（会话内漂移即失效），不允许静默替换内容。总快照仅先取入口 `index.html`，其余路径按 WebView 实际请求经 `resolve(path)` 代理。读取只连接数字地址 `127.0.0.1`，不使用系统代理、不跟随重定向、不接受压缩响应、不保存 Cookie，每个响应只接受 HTTP 200 且总量有界；默认 2 秒连接、5 秒读取超时。探活入口最多等待 15 秒，失败返回固定错误而非发布文档。

来源经 `WebPluginAssetLease.resolve` 注入 `WebPluginResources`：固定快照命中的路径仍走本地快照，未命中的路径才经宿主 resolver；非本域来源、非 GET 方法或已关闭来源一律固定 403。页面仍使用独立随机 HTTPS 来源，CSP 不放开 fetch/WebSocket/iframe/Worker，资源请求绝不回退真实网络；页面不获得 loopback 地址或任何凭据。本片当时未登记应用能力，下一节补上两个只读服务能力。这是应用层代理门控，不隔离同 UID 的沙箱代码。

导航入口为 `PluginPageDestination("tianyan.tool-service", "main", mapOf("web.tool.id" to toolId))`，由 `feature:navigation` 按参数键 `web.tool.id` 分发到 `ToolServicePluginScreen`。`plugin.ui` 不依赖 `runtime`，HTTP 读取留在 `runtime`，仅通过 lambda 注入，无需变更依赖白名单。

验证命令（无 API Key）：

```powershell
.\gradlew.bat :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.*" :plugin:ui:testDebugUnitTest :feature:settings:compileDebugKotlin :app:testDebugUnitTest --tests "top.tianyan.app.di.*" :app:assembleDebug architectureCheck --continue --console=plain
```

本片验收：新增 12 项测试通过（`IndexedManagedWebAssets` 7、`ToolServiceWebSource` 4、`WebPluginResources` 宿主 resolver 1），覆盖入口钉哈希、后续路径按首字节解析、响应漂移整源关闭、非法/穿越路径不请求、合计字节预算、不可用/已关闭来源、原生认证头、非法原生目标拒绝、远端后端拒绝、探活失败不发布文档。全库 `test :app:assembleDebug architectureCheck --continue` 通过，含 36 模块架构门禁、应用 DI 图。为守尺寸棘轮抽出 `ToolDetailAvatar.kt`、`ToolDetailRoute.kt`。未调用真实模型 API，未做真机 WebView 验收。外部分包声明工具服务来源见文末「Phase C 收尾」节；任意启动命令仍不接受，WebSocket 不开放，开发者全权模式见同节。

## PRoot 启动服务作为页面来源（2026-10-19）

同一入口 `PackagedWebPluginSources.openToolService(toolId)` 现在按原生运行状态择一：服务已在运行 → 上节 `ToolServiceWebSource`（只读、不自启）；未运行 → 新增 `ProotToolServiceSource`，由页面持有并启动**工具自持的本地服务**，关闭页面只回收本次实例。

`ProotToolServiceSource` 参数化 `ProotWebAssetService` 的固定程序假设：**不复用 4 KiB bootstrap**（服务端由工具自身提供，宿主不写入脚本或管道），改为就绪探测——在 `127.0.0.1:descriptor.port` 上以有界 `Socket.connect` 轮询（语义同 `ToolServiceController.isPortOpen` / `LocalServiceLauncher.awaitPort`），进程退出或运行环境变更即失败，整体等待最多 15 秒。启动仍走 `LinuxRuntime.startBackground(id, ShellCommand(commandLine, environment), toolId=ownerPluginId, type=SERVICE, distroId)`，停止只用 `stopBackgroundInstance(owned)` 精确回收本实例，绝不停工具中心既有服务或其它进程。

命令、环境、端口与路径全部来自原生解析，页面不接受命令/端口/URL/环境/脚本：端口与路径来自已安装清单（经 `ToolManager.serviceSpec`），`launchCommand` 与 Provider/环境合并由 `ToolRuntimeAdapter.serviceLaunch()` → `GenericRecipeInstaller.serviceLaunch()` 提供，`ToolManager.serviceLaunch(toolId)` 用**已安装版本清单**（`packageOperations.runtimeManifest`）组装 `ProotServiceDescriptor`。`ProotServiceDescriptor` 构造时校验端口范围、基础路径、非空有界启动命令、环境键名（`[A-Za-z_][A-Za-z0-9_]*`）与环境值上限。

资源读取复用上节的 `IndexedManagedWebAssets`：活服务的文件集合事先未知，故**首次响应即钉住该路径 SHA-256**，同一路径后续不同字节、超过 64 个路径或超过 2 MiB 即整源关闭；漂移同时使来源 `isAvailable` 变假，页面收到固定 403 而非新内容。`ProotToolServiceSource.isAvailable` 同时纳入来源关闭、启动失败、目标不适配（非本机 / 未就绪 / 发行版切换）与所持进程存活。

**分配前拒绝**：本地后端且运行时 `Ready`、发行版匹配，且 `runtime.supportsBackgroundInstanceStop` 为真时才允许构造；远程、未就绪或不支持单实例停止的后端在构造阶段直接 `check` 失败，不分配进程。停止失败保留原句柄，重试不停其它进程（与 PRoot 静态来源同口径）。

枚举：`WebPluginPackagePolicy` 新增常量 `PROOT_SERVICE_SOURCE = "PROOT_SERVICE"`（与 `TOOL_SERVICE_SOURCE` 并列）；该片 `validate()` **仍不放行**——外部分包引用服务由文末「Phase C 收尾」节经显式 `serviceToolId` 放行，避免 UI 先承诺运行时尚未准入的能力。

验证命令（无 API Key）：

```powershell
.\gradlew.bat :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.*" :tools:testDebugUnitTest :feature:settings:testDebugUnitTest :plugin:ui:testDebugUnitTest architectureCheck --continue --console=plain
```

本片验收：新增 `ProotToolServiceSourceTest` 9 项，覆盖远程/未就绪/不支持实例停止后端分配前拒绝、描述符越界拒绝、就绪探测成功后钉哈希快照与按路径解析、同路径字节漂移整源关闭、就绪期间进程退出不发布文档、就绪超时回收已启实例、环境（发行版）切换失效、停止失败保留实例与重试隔离、准备态关闭后不再启动。为守尺寸棘轮把 `InstallEvent` 脱敏/日志与卸载结果映射抽到 `ToolInstallEventMapping.kt`（`ToolManager.kt` 951 → 923）。全部 fake / MockWebServer，无 API Key 全绿；未做真机 PRoot/WebView 验收。动态读取见下节；外部分包服务声明与开发者全权模式见文末「Phase C 收尾」节。

## 工具服务的有界动态读取与分块流（2026-10-19）

工具服务页面现在可以在**本服务上**做有界动态读取：CSP 仍为 `connect-src 'none'`，WebView 绝不直连 loopback，动态数据一律走 `window.tianyanPlugin.postMessage()` → `PluginCapabilitySession` → 原生代理 → `127.0.0.1`。新增两个宿主登记能力：`host.service-request`（单次读）与 `host.service-stream`（分块流），由 `WebPluginServiceCapabilities` 工厂产出并在 `ToolServicePluginScreen` 登记。

**路径放在 `text`，不是 `target`。** 消息信封仍是 `{id, action, target [, text]}`，`target` 固定为 `service`：授权按 (动作, 目标) 维度缓存，页面对同一服务的多个路径只需一次会话授权；若把路径写进 `target`，每个新路径都会被当成新能力而重新弹窗。相对子路径经 `PluginCapabilityInput.validServicePath` 轻量校验（非绝对、无 `..`/`.` 段、长度 ≤256、字符限 `[A-Za-z0-9_./-]`，因此没有 scheme/host/query/空白），原生侧再由 `ManagedWebAssets.safePath` 逐段复核（排除空段与保留的 `theme.css`），两层都通过才发出请求，否则一个包都不发。

**单次读**由 `ManagedWebServiceClient.read` 执行：数字 `127.0.0.1` + 原生基础路径 + 可选原生认证头，无系统代理、不跟随重定向、HTTP/1.1、只接受 200 与 identity 编码、固定 UTF-8 解码，最多 32768 字符（与 session 的 `result_too_large` 同口径）；越界、非 200 或不可用一律回复固定失败，不抛出细节。

**分块流**刻意**不是**新的会话机制，而是一次普通的单发能力调用：执行器通过 `WebPluginStreamBridge` 在**同一请求 id** 下反向推送 `{id,success,text,more:true}` 分块，收尾仍由既有 `{id,success,text:""}` 信封标记结束。因此它天然继承单页一个在途（`busy`）、单次或 5 分钟会话授权、`revoke()` / `close()` 取消在途 Job，以及 10 秒执行超时（= 单流时长上界），无需改动 `PluginCapabilitySession` 的准入与既有测试。`WebPluginStreamBridge` 限制单块 ≤4096 字符、单流 ≤65536 字符，且只在恰好绑定了一个请求 id 时推送；一页同时只能有一个在途流，`WebPluginView` 在调用前 `attach`、结束后 `detach`，视 WebView 关闭会 `close` 桥并让 `push` 返回 false —— 页面停止接收时生产者（native 读取）立即停止。取消异常始终向上传播，不被 `runCatching` 吞掉。

**真 WebSocket 不做**：`connect-src` 仍为 `none`，端口、基础路径与凭据始终留在原生侧；分块流已覆盖「持续获取」的实际需要，开放 WebSocket 只会扩大攻击面。

验证命令（无 API Key）：

```powershell
.\gradlew.bat :plugin:runtime:test :plugin:ui:testDebugUnitTest :runtime:testDebugUnitTest --tests "top.tianyan.app.runtime.service.*" :feature:settings:testDebugUnitTest architectureCheck --continue --console=plain
```

本片验收：`ManagedWebServiceClientTest` 7 项（原生基础路径与认证头、外域/穿越/超长路径不发出请求、来源失效或关闭后不答、超限与非 200 一律脱敏为失败、分块流按预算结束、预算耗尽或消费者停止即停止、越界预算拒绝）；`PluginCapabilityInputTest` 3 项（相对路径白名单、仅服务动作接受路径载荷、动作集合与登记能力一致）；`WebPluginStreamBridgeTest` 5 项（单流互斥与分块信封、detach 释放槽位、单块/总量预算、未绑定或关闭即停止、越界预算拒绝）；`WebPluginServiceCapabilitiesTest` 2 项（只登记两个只读能力、页面定义可直接接受）；`WebPluginAssetLeaseTest` 增 3 项（固定快照无动态路径、漂移或关闭后不答动态读取、流取消向上抛出）；`WebPluginMessagesTest` 增 2 项（服务请求只在 `text` 携带相对子路径、分块信封形状）。全部 fake / MockWebServer，无 API Key 全绿；未做真机 PRoot/WebView 验收。

## Phase C 收尾

- **更多宿主动作（C1）**：外部分包能力白名单显式枚举为 `APP_INFO / CLIPBOARD_WRITE / INSTALL_APK / LOGCAT`；`install-apk` 每次确认、路径只做形状校验并由 `HostBridgeApkInstall` 原生复核沙箱根，`logcat` 由宿主固定为 `{"lines":200}`。
- **外部分包服务来源（C3）**：`plugin.webSource` 可为 `TOOL_SERVICE`（只读引用已运行服务、绝不自启）或 `PROOT_SERVICE`（打开时启动并仅回收本实例），且必须给出 `serviceToolId`（已安装 PROCESS 工具的小写 ID）；两类来源都要求 `integrity` 为空，端口/路径/命令/环境一律原生解析，包内不接受地址、凭据、端口或命令。来源进入导入确认与不可变版本比较。`validate()` 现在确实放行（此前第「外部 WEB 的固定 PRoot 来源」片只登记未准入）。
- **只读长期授权（C4）**：只读能力（页面 `allowSession`）的会话授权可缓存到 30 分钟（`longLivedGrantMillis`，上界 1 小时），写能力（`clipboard-write`、`install-apk`）仍逐次确认、寿命 ≤5 分钟；授权不落盘，页面/进程/代次变化即清空重认。授权弹窗文案为「允许此页面（只读能力，最长 30 分钟）」。
- **开发者全权模式（C2）**：沙箱凭据默认动作集移除 `host.shell`，仅持久开发者模式经 `FullAccessGrant` 升权下发；应用层门控，非强隔离。细节见 [`PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md) 第 30 节与 [`SECURITY_SURFACE.md`](SECURITY_SURFACE.md)。

