<p align="center">
  <img src="assets/logo/tianyan-logo.png" width="96" alt="天衍 Logo" />
</p>

<h1 align="center">天衍 · Tianyan</h1>

<p align="center"><strong>面向 Android 的随身智能体工作台：在一部无需 Root 的设备上运行 Linux、管理代码项目，并让智能体协助完成实际开发任务。</strong></p>

<p align="center">Linux 运行环境 · 智能体引擎 · 终端与工作区 · 浏览器调试 · 设备诊断</p>

<p align="center">
  <code>v0.16.0</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin · Jetpack Compose</code>
</p>

<p align="center">
  <a href="https://github.com/J09715/Tianyan/releases/tag/v0.16.0">下载 v0.16.0</a>
  ·
  <a href="https://github.com/J09715/Tianyan/actions">构建与测试</a>
</p>

---

## 天衍是什么

天衍把开发环境、任务执行和设备工具整合到 Android 手机上的一个工作台。它不依赖 Root 权限：Linux 用户空间通过 PRoot 运行，智能体通过可配置的模型接口理解任务、调用工具并持续报告执行状态。代码、终端、浏览器、Git、工作流和设备诊断可以在同一项目上下文中协作。

天衍面向需要移动开发、远程排障和自动化任务执行的开发者。你可以在设备上准备 Linux 环境和项目目录，用终端或图形工具编辑、构建与检查项目，也可以交给智能体执行跨工具任务，并通过审批、运行状态和会话历史掌握执行过程。

## 能力概览

| 工作区 | 能力 |
| :--- | :--- |
| **智能体** | OpenAI 兼容接口与 Anthropic Messages API；流式对话、模型配置、工具调用与审批、任务计划、多智能体调度、会话分支与恢复。新建会话可选择红队模式，目标、范围、阶段与红队状态按会话隔离。 |
| **Linux 环境** | 基于 PRoot 的 ARM64 用户空间；支持多个 Linux 发行版、RootFS 导入与校验、持久目录挂载、镜像源配置及环境诊断。 |
| **终端与项目** | 原生 PTY、多终端会话、项目导入与浏览、Git 操作、代码差异查看，以及沙箱中的后台构建。 |
| **浏览器调试** | 多标签 WebView、CDP 调试、断点与网络请求拦截、页面 Hook 和请求记录。 |
| **工作流与工具** | 可视化 DAG 工作流、定时任务、MCP 服务、开发工具环境安装与依赖管理。 |
| **设备诊断** | 无线 ADB 配对与发现、设备状态检查、Android 与 Linux 侧日志查看。 |
| **文件与协作** | 共享工作区、局域网 WebChat、FTP 文件传输，以及可选的端侧本地模型运行能力。 |

## 红队模式

红队模式是天衍的会话级 Agent 类型。新建会话时选择红队模式，进入当前对话后绑定目标与授权范围；目标、阶段与作战数据只存在于当前会话，切换会话不会看到其他会话的目标或记录。子智能体继续使用天衍原生子代理，不引入第二套编排实现。

工作区由原生 Compose 渲染，按上游 Web 控制台的维度分组展示：资产、漏洞、凭据、访问会话、WebShell、隧道、攻击链、评分、攻击文件、知识库与技能。工作区顶部额外提供两块现场视图：

- **资产图谱**：`C 段 → 资产`，按内网 / 外网分区。归段与智能体侧 `asset_graph` 共用同一份实现（IPv4 走 `/24`、IPv6 走 `/64`），避免出现「工具报 3 个 C 段、面板画 4 个」；域名等非 IP 目标归入「未分类」而不是被拼成脏网段
- **技能体检**：进面板自动跑一次，也可手动重检。缺环境变量/工具直接标出并给出修法，不必等进了靶场才发现

Agent 侧通过统一的 `redteam` 工具工作，始终作用于调用它的那个会话：

| 能力 | action |
| :--- | :--- |
| 会话与预检 | `session_info`、`session_check`、`sessions`、`preflight`、`roles` |
| 角色提示词 | `role_prompt`、`role_prompt_reset`（会话级覆盖，未设置时回落内置职责） |
| 角色派发 | `role_dispatch`（合成「角色约束 + 任务 + 授权范围 + 当前态势」的可直接派发描述，并同时占位）、`group_slot`（在跑角色与剩余名额） |
| 并发闸门 | `agent_slot`（`status`/`acquire`/`release`，按会话隔离）、`group_slot`（在跑角色与剩余名额） |
| 事实写入 | `asset_add`、`vuln_add`、`credential_add`、`access_add`、`webshell_add`、`tunnel_add`、`chain_add`、`attack_file_add`、`score_hit`、`poc_add`、`http_evidence_add`、`knowledge_add`、`skill_add` |
| 事实更新 | `asset_update`、`vuln_update`、`credential_update`、`access_update`、`webshell_update`、`tunnel_update`、`poc_update`（必须带 `id`） |
| 资产图谱 | `asset_link`（写关系边）、`asset_graph`（按 C 段取子图，节点上限默认 300 / 硬上限 1000）、`domain_index`、`asset_stats`、`asset_timeline`、`web_list` |
| 攻击路径 | `attack_path`、`chain`、`attack_chain` |
| 单条读取 | `asset_get`、`vuln_get`、`poc_get` |
| PoC 使用 | `poc_use`（使用计数写回记录） |
| 事实查询 | `fact_query`、`asset_query`、`vuln_query`、`credential_list`、`webshell_list`、`tunnel_list`、`attack_file_list`、`score_list`、`poc_list`、`poc_search` |
| 报告 | `report`、`score_report`、`report_targets` |
| 评估登记 | `asset_test`（"未测试/测试中/已测试/被封禁/已放弃/无攻击面"状态机，测试记录追加、剩余攻击面覆盖、被封禁计数）、`asset_assess`（易打性评估：优先级 / 预期成果 / 判断理由） |
| 评分规则 | `score_points`（25 个得分点 / 8 个类别 / 8 条通用规则 G1–G8） |

### 评分引擎

移植自上游 `score-rules.js`，判分口径与上游**逐例一致**（由对照测试保证，见下）：

- **25 个得分点**，对齐《突破入侵类得分规则（合并版）》，按 8 个类别分组：一般系统 / Web 应用 / 集权系统 / 大数据 / 网络基础设施 / 文件存储 / 模型相关 / 突破网络边界
- **8 条通用规则 G1–G8**：G1 权限取高只计一次、G2 数据成果另行计分、G3 上限针对单个防守单位、G4 设备按台计分、G5 数据规模翻倍、G6 IPv6 ×3（不突破原上限）、G7 证明材料、G8 兜底
- **去重口径** `service` / `system` / `target` / `none`：同口径只保留分值最高一条，同分取更早记的
- **规则上限**：同一 `rule` 的计分命中按分值从高到低累计，超出 `cap` 的标为不计分并给出原因
- **`self_created` 不计分**：自己注册自建的账号不参与竞争也不占位
- **隧道真实性**：只有通道一端在目标侧（`target-outbound` / `target-http` / `target-agent`）才算跨越靶标边界，自建 VPS 上的代理不算突破

`score_report`、`report` 与攻击链共用同一份实现，三处给出的总分必然一致——上游为此把规则抽成唯一实现，因为三处各写一遍必然漂移成不同口径。得分点定义由上游脚本程序化生成，禁止手改。

### 复现入口

报告里每条得分都要有「可照做」的复现入口，而不是只有一句「没有原始请求记录」。数据本来就在事实库里（攻击步骤的 tool、漏洞的目标 URL、HTTP 证据的原始报文），报告把它整理成：

- **可重放的原始报文**：能直接粘进 Yakit Repeater / Burp 的完整请求（含请求行、Host、Content-Type、按 UTF-8 字节数计算的 Content-Length）
- **可直接跑的命令**：真实的 curl 命令**原样保留**；否则按 URL 与方法拼一条；不认识的形态宁可留空并给补录指引，不编造
- **模板填充**：数据库/终端/隧道类模板（`mysql -h <host> -u <user> …`）用本条得分已知的信息填上已知部分，剩余占位符**保持原样并如实列出**

两条底线：**合成的东西必须标注来源**（`synthesized=true`）——推断出来的请求与真实抓包在可信度上不是一回事，不能让验收人把推断当实证；只在信息足够时合成，信息不足就留空并给补录指引。

> 与上游的一处**刻意差异**：上游 `buildHttpRequest` 把 `Host` 头构造出来却从未拼进结果，导致所有合成报文都缺 HTTP/1.1 的必填头。移植版补上该头，并在对照测试里把这个差异钉死，避免以后被当成漂移改回去。

### 开工前体检

**技能能列出来 ≠ 能跑**。技能正文里写着本机路径、环境变量、VPS 地址，缺 `FOFA_KEY`、工具没落到 toolkit 目录、VPS 还是占位符，都要等真正动手才发现——那时候人已经在靶场里了。所以 `preflight` 开工前跑一次，缺什么直接要，并给出怎么修。

判定维度（都是能从技能正文里客观读出来的）：

- 技能正文能否加载（读不到判 `unknown`，**不误判 `broken`**）
- 必需环境变量：`process.env.X`、`os.environ["X"]`、`${X:?必填}`；带默认值的 `os.environ.get("X", "…")` 有兜底**不算必需**；裸 `$X` 不判定（技能里大量出现 `$TARGET` 这类占位）
- 正文引用的本机路径（toolkit / bin / local 下的绝对路径、`$DSH_HOME`、`~/`），支持 `$DSH_HOME` 与 `~` 展开
- 外部基础设施占位符（`<你的VPS_IP>` 等）：**先看 `REDTEAM_VPS_HOST` 有没有配，再看占位符**——顺序反了会让配好 VPS 的机器上这几个技能永远显示不可用
- 同名技能被排在后面的根「盖住」时如实说明，而不是让用户以为环境没配

**不判定**：语义正确性、权限、目标可达性——那些只有真打一次才知道。

每条问题都同时给出**修复方法**，而不只是说「哪里不可用」：缺哪个工具就给出该工具的装法（几十条工具修法表由上游程序化生成），同一个工具的多个别名（`impacket-wmiexec` / `impacket-secretsdump`）合并成一条，避免面板被撑爆。

### IP 与网段

归段与内网/外网判定移植自上游 `ip-utils.js`，同样有对照测试：IPv4 走 `/24`、IPv6 走 `/64`（`2001:db8::1` → `2001:db8:0:0::/64`，缩写里的 `db8` 必须保留）；认不出的输入**原样返回**，绝不拼出 `2001:db8::1.0/24` 这种脏 CIDR——那会让资产测绘面板多出假网段、资产归属也错。内网判定覆盖 `10/8`、`192.168/16`、`172.16-31/12`、`100.64-127/10`（CGNAT）、`127/8`、`169.254/16`，以及 IPv6 的 ULA 与链路本地。

判分与复现逻辑的正确性由**对照测试**保证：夹具直接用上游 Node 实现跑出来，Kotlin 移植版必须逐例一致。手写期望值只能证明「我实现了我以为的规则」，跑上游才能证明「我实现的是同一套规则」。

事实记录保留上游的原始字段（IP、端口、服务、指纹、来源 provenance、关系 src/dst/relation 等）并以 JSON 存入会话事实库；结构化写入使用稳定 ID，可重复提交覆盖同一条记录。工具 action 清单与协调器实现由 `RedTeamToolSchema` 单一来源驱动，并有契约测试断言「宣告的动作必须已实现」，防止再次出现枚举宣告但无分支的情况。真正的探测与验证动作仍通过天衍既有的 `base`/`process`/`MCP` 执行，并遵循当前会话的审批模式与 scope。

### 角色

角色 code 与上游 `ROLE_TITLES` 逐条对齐，白名单从枚举派生而非写死：

| code | 角色 | 可派发 |
| :--- | :--- | :--- |
| `plan` | 主会话（指挥） | 否，主会话本身 |
| `recon` | 信息收集 | 是 |
| `assess` | 资产梳理 | 是 |
| `vuln-scan` | 漏洞发现 | 是 |
| `exploit` | 漏洞利用 | 是 |
| `internal` | 内网渗透 | 是 |

`role_dispatch` 把角色约束、本轮任务、授权目标与 scope、当前资产与评分态势合成一份可直接交给 `invoke_subagent` 的描述，**并在派发时即占用并发名额**——先查名额再派会被别的角色抢走，产生「以为没满却派了第 4 个」的竞态。子代理继续使用天衍原生子代理，不引入第二套编排实现。

### 并发上限

同时派几个执行智能体是使用中最常调的一项，所以做成可配置而不是写死：

- 生效顺序：设置项 → 环境变量 `REDTEAM_MAX_AGENTS` → 默认 3
- 硬上限 10：再多也不会更快，同一个 API Key 的并发/速率限制会先到，反而扰动测试
- 越界值收敛到 `1..10`；填 `0` 视为未设置（让位给环境变量），而不是被解释成「只允许 1 个」
- 设置项落在 `$DSH_HOME/redteam/settings.json`，与事实库同根，随库一起备份迁移

### 写入校验

写库前的校验是**安全边界**，不是格式美化：

- **路径穿越防护**：攻击文件与 PoC 读的是库里存的 path 列，而那个列是智能体自己写进去的——也就是不可信输入。一旦填成 `/etc/passwd` 或 `~/.ssh/id_rsa`，界面上点一下就把文件读出来了。读路径与写路径共用同一处判定，越界按「查不到」处理，**不静默返回内容**
- **WebShell 类型/状态归一化**：支持大小写与中文别名（冰蝎 / 哥斯拉 / 蚁剑 / 自研），认不出**当场报错而不是静默存原文**——面板的「用户连不上」红标与会话页的连接口令复制都按这个字段判断，存进一个拼错的值不会报错，只会让这两处判定静默失效

## 日志与崩溃排查

开发者控制台提供「运行日志」区块：

| 操作 | 作用 |
| :--- | :--- |
| 查看运行日志 | 等宽字体弹窗展示 `runtime.log` 尾部（默认 10 万字符） |
| 查看崩溃报告 | 展示最近一次未捕获异常，含版本、机型、线程与完整栈 |
| 复制运行日志 / 复制崩溃报告 | 一键进剪贴板，便于直接反馈 |
| 清空运行日志 / 清空崩溃报告 | 复现前清零，避免旧记录干扰 |

日志优先写入公共 `Download/Tianyan`，无「所有文件访问」权限时回退应用私有目录，保证不静默丢失；崩溃报告会在下次启动后导出到 `Download/Tianyan/crash-reports`。

## 获取应用

最新稳定版本：**[天衍 v0.16.0](https://github.com/J09715/Tianyan/releases/tag/v0.16.0)**

- APK：[`Tianyan-v0.16.0-debug.apk`](https://github.com/J09715/Tianyan/releases/download/v0.16.0/Tianyan-v0.16.0-debug.apk)
- SHA-256：[`Tianyan-v0.16.0-debug.apk.sha256`](https://github.com/J09715/Tianyan/releases/download/v0.16.0/Tianyan-v0.16.0-debug.apk.sha256)
- 包名：`top.tianyan.app.debug`
- 签名：由项目稳定密钥签名；校验和及证书 SHA-256 指纹见 Release 说明。

该 APK 与先前使用相同包名和签名证书的版本兼容，可直接覆盖安装。不要使用第三方工具重新签名，否则会改变证书并导致 Android 拒绝覆盖更新。

## 从源码构建

### 环境要求

- JDK 21
- Android SDK：compileSdk 37、targetSdk 37、minSdk 29
- Android NDK：`30.0.15729638`
- CMake：`3.22.1`
- Gradle Wrapper：以仓库配置为准

### 构建步骤

```bash
# 首次检出后准备 PRoot ARM64 运行时
bash tools/prepare-proot-runtime.sh

# 执行架构检查和单元测试
./gradlew architectureCheck --console=plain
./gradlew testDebugUnitTest --console=plain

# 编译 Debug APK
./gradlew :app:assembleDebug --console=plain
```

APK 输出目录：`app/build/outputs/apk/debug/`。Release workflow 负责准备 ARM64 原生 PTY 库、使用项目稳定签名密钥构建，并验证 APK 的包名、对齐和签名指纹。GitHub Actions 会运行全量 JVM 单元测试和 APK 构建；工作流与运行结果见 [Actions](https://github.com/J09715/Tianyan/actions)。

## 项目与许可

天衍以独立产品和品牌持续开发。项目中包含基于第三方开源软件构建或分发的组件，其来源、版权与适用条款记录在 [`NOTICE`](NOTICE) 中。仓库当前没有根目录 `LICENSE` 文件；在正式增加许可证声明前，除 NOTICE 中明确许可的第三方内容外，不应推定本项目整体采用某一开源许可证。
