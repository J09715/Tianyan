# `.txplugin` v2 包协议

实现入口：`plugin/packages` 的 `PluginPackageProtocol`、`PackageInstallPolicy`；Android 导入与安装由 `tools` 适配。作者侧可用 `plugin/cli`（`tianyan-plugin check|pack|inspect`）校验工程并生成归档，用法见 [`PLUGIN_DEVELOPMENT_GUIDELINES.md`](PLUGIN_DEVELOPMENT_GUIDELINES.md#v2-作者-cli)。v1 保持兼容，统一目录身份为 `legacy.tool-<toolId>`，原工具详情、启动与卸载入口保留。

## 包与身份

ZIP 根目录只允许 `manifest.json` 和 `payload/`。v2 严格拒绝未知字段；清单最大 1 MiB，解压合计最大 8 GiB，条目最多 20,001。嵌套压缩包不在导入阶段展开。版本目录不可覆盖，升级必须递增版本。

`id` 是旧工具存储键，保持小写字母、数字和连字符；`plugin.id` 是独立的点分插件身份。第三方不能声明 `tianyan.*`、`legacy.*` 身份。v2 不能覆盖内置或在线工具，已有 v2 身份不能改名、切换 PROCESS/WEB 形态或降为 v1。目前支持本地自包含 PROCESS 包和受限静态 WEB 包；不加载外部原生代码。以下进程安装、资源与收据规则针对 PROCESS；WEB 规则见文末。

最小可安装示例（空 payload，安装时生成脚本）：

```json
{
  "schemaVersion": 2,
  "id": "hello-plugin",
  "name": "Hello plugin",
  "description": "Offline process plugin example",
  "version": "1.0.0",
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE",
  "installSteps": [
    "printf '#!/bin/sh\\necho hello\\n' > \"$TIANYAN_TOOL_DIR/bin/hello-plugin\"; chmod 755 \"$TIANYAN_TOOL_DIR/bin/hello-plugin\""
  ],
  "launchCommand": "hello-plugin",
  "verifyCommand": "hello-plugin",
  "plugin": {
    "id": "example.hello",
    "api": {"minimum": 1, "maximum": 1},
    "execution": "PROCESS",
    "extensions": [{"id": "launch", "point": "PROCESS"}],
    "dependencies": [],
    "capabilities": [],
    "resources": [],
    "integrity": {}
  }
}
```

实际携带文件时，`integrity` 必须逐一列出 payload 下所有普通文件的相对路径和小写 SHA-256。示例 `payload/run.sh` 对应键 `run.sh`；多一个、少一个或内容不符均拒绝。安装复制时再次校验，v2 不改写换行符或 BOM。v1 保留旧 `.sh` 换行修复。

## PROCESS 的 Agent 扩展

`plugin.harness` 可声明受限 MCP 工具、Provider、工作流节点。`entry` 必须是 `commandLinks` 中的私有可执行入口，宿主使用 `/opt/tianyan/tools/<id>/bin/<entry> --mcp` 启动；元数据发现不运行进程。示例：

```json
{"entry":"hello-plugin","tools":[{"id":"echo","description":"返回文字","schema":{"type":"object","properties":{"text":{"type":"string","maxLength":1024}},"required":["text"],"additionalProperties":false}}],"nodes":[{"id":"echo-step","description":"返回文字","rpcTool":"echo","schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}],"providers":[{"id":"offline","rpcTool":"provider","api":"OPENAI_COMPLETIONS","models":["plugin-default"]}]}
```

`plugin.extensions` 必须精确对应 TOOL/WORKFLOW/PROVIDER 声明，可额外保留 `launch/PROCESS`。总数最多 32；Schema 使用共享严格子集。WEB 包不接受 harness 字段。

仅安装收据对应的包可调用。工具能力域带安装版本指纹，升级后旧审批不能路由至新版；工作流用稳定包身份，已捕获执行器仍在调用前检查安装版本。调用与安装/卸载互斥，绑定当前本地发行版，切换到远程后拒绝。单次 MCP 执行最多 120 秒，输出最多 256 KiB UTF-8，随后关闭连接；回收失败时阻止下一次分配并先重试回收。

Provider 标识为 `plugin.<plugin.id>.<provider.id>`，须在模型设置显式选择，模型列表来自包元数据。宿主不继承全局模型密钥，携带 Key 的请求拒绝；可在插件内部管理其所需凭据。Provider 的 MCP 返回 text 是 JSON：`content`、可选 `reasoning`、`toolCalls`（每项 `id/name/arguments`）。流式 API 当前为完整响应后分发文字的兼容模式，不提供逐 token SSE。卸载后禁止退回普通 HTTP Provider。

开发者模式不直接赋予宿主权限。原生设置按发行版、包身份、安装版本确认最长 30 分钟的 Shell/APK/Logcat 代理准入；撤销、超时、关闭开关、重启及安装/卸载使旧凭据失效。PRoot 仍与 App 同 UID，这不是恶意代码强隔离。原生 IPC、后端替换尚未开放。

可运行示例位于 `project-template` 的 `tianyan-plugin/process`，使用 Python 标准库并保持离线。

## 依赖版本规则

v2 使用严格 SemVer，含预发布优先级，build metadata 不影响排序。插件依赖与旧 `dependencies` 运行时依赖字段分开：

```json
{"id":"example.sdk","minimumVersion":"1.0.0","maximumVersionExclusive":"2.0.0"}
```

下界包含、上界不包含；省略上界表示无上限。依赖必须先安装，宿主不会自动下载。安装和升级同时复核所有已安装依赖者；缺失、版本不符、循环和身份冲突拒绝。被依赖插件不能卸载。

检查依据是同一发行版 DB `installedVersion` 对应的版本收据，不采用刚导入的新清单。收据保存身份、版本、依赖、能力及资源，不保存环境变量、安装脚本或 Provider 凭证；`packageVersion` 只关联不可变包目录，兼容旧工具把命令输出横幅记作安装版本的情况，旧 v2 收据缺省时使用 `installedVersion`。v2 收据缺失或损坏时拒绝继续猜测。旧工具在导入替换清单前补记兼容收据。

## 能力与资源

能力项为 `{"name":"NETWORK","target":"example.com"}`，当前支持 NETWORK、WORKSPACE_READ、WORKSPACE_WRITE、LOCAL_WEB。确认页展示相对已安装收据新增的请求（含 target 变化），并绑定预览清单。它们是声明，尚未实现逐能力执行授权；脚本仍使用现有 PRoot 环境与 Provider 注入路径，不能视为沙箱。

资源示例：

```json
{"key":"example.sdk","path":"/opt/shared-sdk","sharing":"SHARED","contentSha256":"<64 位小写 SHA-256>","removal":"RETAIN"}
```

允许本插件 `/opt/tianyan/tools/<id>`、`/opt/tianyan/data/<id>`、`/opt/tianyan/shared/*` 或独立 `/opt/*` 路径。独占为默认值；共享必须具有相同 key、完整路径和内容指纹。不同插件的目录祖先/子目录重叠拒绝，同 key 指向不同路径也拒绝。框架自动把程序目录、数据目录和命令入口纳入独占声明，检查跨插件冲突。

声明中的资源目前只接受 RETAIN：卸载不自动删除任意全局路径。v2 禁止自定义卸载脚本，框架清理工具目录、可选数据和收据中的命令入口。暂不允许升级移除原有命令入口；需先卸载旧版本。资源内容指纹是共享一致性声明，不能证明安装脚本实际写入的文件内容；任意脚本也能越出声明路径，因此这些检查面向协作插件，不构成恶意代码隔离。

## 事务与恢复范围

安装/卸载引用检查串行执行；通用安装器固定清单、payload 版本与目标发行版，切换 UI 选择不能重定向安装。v2 验证或命令入口创建失败不会被降级为“文件存在即成功”，成功版本取清单版本而非程序输出横幅。

重装和升级都先保留已有程序快照，再执行候选版本配方与安装后验证，不要求损坏的旧版本预先通过验证。导入只切换安装候选；启动、交互终端、验证、服务端口和卸载按实际安装版本读取对应包清单。安装失败或取消后，即使候选版本仍为新版，旧版本的运行配置也保持原样；对应已安装包清单缺失时拒绝用新版配置替代。

保留各版本包和原有工具目录快照事务，不提供任意版本切换 UI，也不保证任意脚本全局副作用回滚。既有事务只覆盖工具目录，收据在提交程序快照前写入；进程死亡窗口及真实 Linux 文件恢复仍需真机验收。全局路径、系统包、命令脚本和 Provider 环境并未变为完整插件事务。保留的全局文件也不是宿主维护的实时资源盘点。

统一管理页同时显示原生页面插件和当前发行版的进程插件/兼容工具。进程插件由工具详情管理，原生页面开关不控制其后台进程；外部 Web/IPC 扩展及能力代理见 [后续方案](PLUGIN_PLATFORM.md)。

## 静态 WEB 包（P5）

设置 → 应用功能插件 → 外部 Web 插件提供独立的导入、打开与移除入口。WEB 包导入成功即可打开，不进入 Linux 工具目录、配方安装、命令入口或进程收据；工具中心的 PROCESS 导入入口拒绝 WEB 包。

使用同一 ZIP 格式、严格 v2 字段、API/SemVer、身份冲突和 SHA-256 规则，`plugin.execution` 为 `WEB`，扩展必须恰好为 `[{"id":"main","point":"PAGE"}]`。固定入口是 `payload/index.html`；最多 63 个普通文件，合计 2 MiB，每个路径最多 256 字符、16 段，只接受普通 ASCII 文件名与目录，不允许查询串、百分号编码或宿主保留的根 `theme.css`。归档本身最多 129 个条目、解压合计最多 3 MiB（含 1 MiB 清单）；流式预览同样有界。声明与实际 payload 文件集合及哈希必须完全一致。

WEB 不接受安装/卸载步骤、启动/验证命令、命令入口、环境变量、运行时依赖、进程服务、旧 `permissions`、插件依赖和全局资源。当前能力白名单为 `{"name":"APP_INFO","target":"application"}`、`{"name":"CLIPBOARD_WRITE","target":"clipboard"}`、`{"name":"INSTALL_APK","target":"apk"}` 与 `{"name":"LOGCAT","target":"logcat"}`，可为空或各声明一次；分别映射到 `host.app-info`、`host.clipboard-write`、`host.install-apk`（安装包内/沙箱根下的 APK）与 `host.logcat`（有界日志）。读取需原生授权；剪贴板写入与安装 APK 每次展示并逐次确认，不能持续授权；`install-apk` 的路径只做形状校验，由原生再核对沙箱根并自行解析真实文件。NETWORK、Shell、工作区/文件权限以及任意回调声明均拒绝。

导入只提交静态文件与原子当前版本指针，不执行页面、安装脚本或模型请求，不授予能力。打开时检查路由中的工具 ID、插件身份、版本与当前版本一致，再读取并重验全部文件；WebView 只接收有界字节快照。升级必须递增 SemVer，只有 build metadata 不同的同优先级版本不能代替升级；同版本不覆盖。升级或移除关闭旧版本准入，原页面不能继承新版本授权；失败导入保留原版本。移除删除确认时的该包全部导入版本，不跟随符号链接，不影响其它插件和全局路径。

完整示例、消息协议和权限边界见 [`PLUGIN_WEB_EXTENSIONS.md`](PLUGIN_WEB_EXTENSIONS.md)。共享 HostBridge 的旧 key 已迁移到宿主签发凭据（见 [`PLUGIN_PLATFORM.md`](PLUGIN_PLATFORM.md) 第 26 节）；PRoot 与同 uid 的风险保持原有边界。

### 可选 PRoot 静态来源

WEB 的 `plugin.webSource` 省略时为 `PACKAGE`，直接打开已验证资源；也可显式指定 `PROOT_STATIC`，选择宿主固定的 Python 静态服务。未知值拒绝，PROCESS 不能声明非默认来源。该字段纳入完整预览确认及不可变版本比较，不能同版本替换；旧版本宿主会按严格未知字段拒绝此新声明。

`PROOT_STATIC` 不开放启动命令、端口、URL、环境变量或包提供的 Python 程序，原 WEB 白名单与静态 CSP 不变。导入不要求 Linux、不运行进程，也不生成 PROCESS 收据；打开时重新验证选中版本的全部字节，准备资源租约，再在当时已就绪的本地发行版启动独立实例。需要 `/usr/bin/python3`，不自动安装。PRoot 包预览会完整验证文件集合、哈希与 4 KiB 启动容量，导入提交前复核，超限包不会切换当前版本。缺失依赖、切换环境或打开时哈希错误会拒绝加载；页面退出、升级及移除回收其实际实例，失败保留清理记录。

生成示例：`tools/package-web-example.ps1 -Proot`（应用信息）或同时指定 `-Clipboard -Proot`（逐次确认复制）。该模式仍仅用于小型静态资源快照，不支持动态 API 或 WebSocket。完整边界见 [Web 契约](PLUGIN_WEB_EXTENSIONS.md)。

### 可选工具服务来源（TOOL_SERVICE / PROOT_SERVICE）

WEB 的 `plugin.webSource` 还可声明 `TOOL_SERVICE` 或 `PROOT_SERVICE`，并必须同时给出 `serviceToolId`（一个已安装 PROCESS 工具的小写 ID，形状 `[a-z0-9][a-z0-9-]{1,63}`）；其它来源携带该字段一律拒绝，PROCESS 也不能声明服务来源或引用。两类服务来源都要求 `integrity` 为空——页面内容全部由被引用工具自述的本地服务提供，包内不接受地址、凭据、端口、命令或环境，端口、基础路径、启动命令与 Provider 环境在打开时由原生解析（`ToolManager.serviceSpec/serviceLaunch`）。`TOOL_SERVICE` 只读引用已运行的服务（绝不自启），`PROOT_SERVICE` 允许页面在打开时启动该工具自述的服务并在关闭时只回收本实例。来源声明纳入预览确认与不可变版本比较；引用工具必须已安装且声明服务，否则打开时拒绝，不进入 Linux 安装、配方或收据。
