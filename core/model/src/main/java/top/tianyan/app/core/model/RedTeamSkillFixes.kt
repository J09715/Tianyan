package top.tianyan.app.core.model

/**
 * 技能修复文案，逐条对齐上游 `skill-fixes.js`。
 *
 * 本文件由上游程序化生成，禁止手改：判定的价值一半在「缺什么」，另一半在「怎么修」，
 * 手抄几十条带中文命令的工具修法必然漂移。需要更新时改上游并重新生成。
 */
object RedTeamSkillFixes {

    /** 仓库自带的一键修复入口（工具下载、FOFA_KEY/VPS 引导都在这一个脚本里）。 */
    const val SETUP = "bash \$DSH_HOME/redteam/setup.sh --yes"

    /** 完全不知道缺什么时的兜底建议。 */
    const val GENERIC_FIX = "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 补齐工具与配置（幂等，可反复跑；缺的会下载，损坏的会删掉重下），改了 `\$DSH_HOME/.env` 后重启 `dsh web` 才生效；仍不行就重新安装 redteam 插件再体检一次。"

    /** 随工具箱压缩包分发（setup.sh 不下载）的东西从哪来 —— 统一口径。 */
    const val TOOLKIT_TARBALL = "项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz`"

    data class ToolFix(val what: String, val install: String)

    /** 逐工具修法：what 是什么，install 怎么装。 */
    val TOOL_FIXES: Map<String, ToolFix> = mapOf(
        "fscan" to ToolFix("内网综合扫描（存活/端口/服务识别/28 类弱口令/未授权/高危漏洞，v2.2.1）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 补到 `\$DSH_HOME/redteam/toolkit/fscan/fscan`；若只下到 `fscan.zip` 没解压，手动解压出 `fscan` 二进制并 `chmod +x`；脚本仍补不上就从 shadow1ng/fscan 的 GitHub releases 下载对应平台二进制（含 `fscan_windows_x64.exe`、`fscan_linux_arm64`）放进该目录"),
        "gogo" to ToolFix("内网测绘与指纹引擎（v2.15.0，主动+被动指纹、关键信息提取、nuclei 模板 POC）", "setup.sh 对 gogo 只做提示、不自动下载（上游资产命名不固定）：跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 看提示，再自己从 chainreactors/gogo 的 GitHub releases 按平台取二进制，主程序放 `\$DSH_HOME/redteam/toolkit/gogo/gogo`，Windows/arm64 变体（`gogo_windows_amd64.exe`、`gogo_linux_arm64`）放同目录，全部 `chmod +x`"),
        "suo5" to ToolFix("suo5：通过 WebShell/HTTP 建立 SOCKS5 隧道的内网突破核心工具（v2.2.0，静态 Go 无依赖）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/suo5/suo5-linux-amd64`；或从 zema1/suo5 的 GitHub releases 取 `suo5-linux-amd64` 放到该路径并 `chmod +x`"),
        "chisel" to ToolFix("chisel HTTP 隧道（服务端与客户端是同一个二进制，v1.12.0）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/chisel/chisel`；或从 jpillora/chisel 的 GitHub releases 取 `chisel_<版本>_linux_amd64.gz`，`gunzip` 后 `chmod +x`"),
        "frp" to ToolFix("frp 内网穿透：`frps` 跑在 VPS 上做服务端，`frpc` 跑在目标上做客户端（同一个 release 包里的两个二进制）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 一次解出 `frpc` + `frps` 到 `\$DSH_HOME/redteam/toolkit/frp/`；脚本没覆盖时就手工从 fatedier/frp 的 GitHub releases 取 `frp_<版本>_linux_amd64.tar.gz`，解压后把 `frpc`/`frps` 放进同一目录并 `chmod +x`"),
        "frpc" to ToolFix("frp 内网穿透：`frps` 跑在 VPS 上做服务端，`frpc` 跑在目标上做客户端（同一个 release 包里的两个二进制）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 一次解出 `frpc` + `frps` 到 `\$DSH_HOME/redteam/toolkit/frp/`；脚本没覆盖时就手工从 fatedier/frp 的 GitHub releases 取 `frp_<版本>_linux_amd64.tar.gz`，解压后把 `frpc`/`frps` 放进同一目录并 `chmod +x`"),
        "frps" to ToolFix("frp 内网穿透：`frps` 跑在 VPS 上做服务端，`frpc` 跑在目标上做客户端（同一个 release 包里的两个二进制）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 一次解出 `frpc` + `frps` 到 `\$DSH_HOME/redteam/toolkit/frp/`；脚本没覆盖时就手工从 fatedier/frp 的 GitHub releases 取 `frp_<版本>_linux_amd64.tar.gz`，解压后把 `frpc`/`frps` 放进同一目录并 `chmod +x`"),
        "subfinder" to ToolFix("被动子域枚举（ProjectDiscovery，v2.16.0，多源聚合）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/subfinder/subfinder`；或从 projectdiscovery/subfinder 的 GitHub releases 取 `subfinder_<版本>_linux_amd64.zip` 解压后放进去并 `chmod +x`"),
        "dnsx" to ToolFix("批量 DNS 解析/爆破/泛解析过滤（ProjectDiscovery，v1.3.1）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/dnsx/dnsx`；或从 projectdiscovery/dnsx 的 GitHub releases 取 `dnsx_<版本>_linux_amd64.zip` 解压后放进去并 `chmod +x`"),
        "naabu" to ToolFix("高速端口扫描（ProjectDiscovery，v2.6.1；SYN 需要 root，非 root 加 `-scan-type c`）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/naabu/naabu`；或从 projectdiscovery/naabu 的 GitHub releases 取 `naabu_<版本>_linux_amd64.zip` 解压后放进去并 `chmod +x`"),
        "httpx" to ToolFix("HTTP 存活/标题/状态码/技术栈探测（ProjectDiscovery 版，v1.12.0；`/usr/bin/httpx` 是 Python httpx 库的 CLI，不是这个）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/httpx/httpx`；或从 projectdiscovery/httpx 的 GitHub releases 取 `httpx_<版本>_linux_amd64.zip` 解压后放进去并 `chmod +x`；命令行里请配合包装脚本 `pd-httpx` 调用（见 `pd-httpx` 条目）"),
        "ksubdomain" to ToolFix("无状态子域爆破（knownsec ksubdomain v0.7，比 dnsx 爆破快，需要 root 发包）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/ksubdomain/ksubdomain`；或从 knownsec/ksubdomain 的 GitHub releases 取 linux 版本压缩包解压后放进去并 `chmod +x`"),
        "ligolo" to ToolFix("ligolo-ng TUN 隧道（`proxy` 服务端 + `agent` 目标侧），chisel/frp 之外的备选通道，需要 root 建虚拟网卡", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/ligolo/proxy` 与 `.../ligolo/agent`；或从 nicocha30/ligolo-ng 的 GitHub releases 取 `proxy_*_linux_amd64.tar.gz` / `agent_*_linux_amd64.tar.gz`，解压后按上面的名字放到 `toolkit/ligolo/` 并 `chmod +x`"),
        "ligolo-proxy" to ToolFix("ligolo-ng TUN 隧道（`proxy` 服务端 + `agent` 目标侧），chisel/frp 之外的备选通道，需要 root 建虚拟网卡", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/ligolo/proxy` 与 `.../ligolo/agent`；或从 nicocha30/ligolo-ng 的 GitHub releases 取 `proxy_*_linux_amd64.tar.gz` / `agent_*_linux_amd64.tar.gz`，解压后按上面的名字放到 `toolkit/ligolo/` 并 `chmod +x`"),
        "ligolo-agent" to ToolFix("ligolo-ng TUN 隧道（`proxy` 服务端 + `agent` 目标侧），chisel/frp 之外的备选通道，需要 root 建虚拟网卡", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/ligolo/proxy` 与 `.../ligolo/agent`；或从 nicocha30/ligolo-ng 的 GitHub releases 取 `proxy_*_linux_amd64.tar.gz` / `agent_*_linux_amd64.tar.gz`，解压后按上面的名字放到 `toolkit/ligolo/` 并 `chmod +x`"),
        "gowitness" to ToolFix("批量网页截图留证（sensepost/gowitness）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装到 `\$DSH_HOME/redteam/toolkit/gowitness/gowitness`；或从 sensepost/gowitness 的 GitHub releases 取 `gowitness-<版本>-linux-amd64` 放进去并 `chmod +x` —— 注意下载必须完整（截断的二进制文件在、权限也对，跑起来却零输出）"),
        "oneforall" to ToolFix("OneForAll 子域收集全家桶（v0.4.5，源码 + `.venv`，慢但全）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 会把源码落到 `\$DSH_HOME/redteam/toolkit/oneforall/OneForAll-0.4.5/` 并建好 `.venv` 依赖；装不上不影响主线 —— 用 `subfinder` + `ksubdomain` 替代即可"),
        "vps" to ToolFix("VPS 登录方式（`REDTEAM_VPS_HOST` / `REDTEAM_VPS_KEY`）与中转脚本 `vps.sh`：反弹 Shell、载荷投递、隧道出口的落地端", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 会引导写入 VPS 地址与私钥路径；私钥放 `\$DSH_HOME/redteam/toolkit/vps/id_rsa` 并 `chmod 600`（连通性用 `ssh -i \$DSH_HOME/redteam/toolkit/vps/id_rsa -o BatchMode=yes <user>@<ip> 'echo ok'` 验）；脚本 `\$DSH_HOME/redteam/toolkit/vps/vps.sh` 随工具箱分发，缺了就从 项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 里解出来"),
        "nuclei-templates" to ToolFix("nuclei 模板库（13,000+ 模板，`nuclei -t` 与 `redteam_poc_search` 都靠它）", "跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 会装到 `~/.local/nuclei-templates`；也可单独跑 `nuclei -update-templates`（频率不超过每周一次）；模板数少于 1000 说明没下全，重跑一次并 `nuclei -tl` 确认模板库没坏"),
        "dirsearch" to ToolFix("目录/文件爆破（Python，v0.5.0，字典全、报告友好，带自带运行时）", "不在 setup.sh 的下载清单里：从 maurosoria/dirsearch 的 GitHub releases 取源码包，放到 `\$DSH_HOME/redteam/toolkit/dirsearch/` （保证 `toolkit/dirsearch/dirsearch` 可执行、`chmod +x`）；临时可用 `feroxbuster` / `ffuf` / `gobuster` 顶上"),
        "behinder" to ToolFix("冰蝎 v4.1 客户端 + 加密马模板（`toolkit/Behinder/server/*.jsp|php|aspx`，WebShell 交付主力）", "由项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 随包分发（setup.sh 不下载）：解出整个 `toolkit/Behinder/`；只需要马模板时保持 `toolkit/Behinder/server/` 七个模板在位即可（无 GUI 也能用 `sed` 换密钥生成）；需要 `java -jar` 所以本机要有 Java"),
        "godzilla" to ToolFix("哥斯拉 v4.0.1 客户端（`godzilla.jar`，GUI 生成 JSP/PHP/ASPX 全加密 payload）", "由项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 随包分发：解出 `toolkit/Godzilla/`；启动需要 Java（`apt install openjdk-17-jre`）与图形会话（`DISPLAY=:10.0`）"),
        "antsword" to ToolFix("中国蚁剑 AntSword（Loader + 源码目录，首次启动要选源码目录 `antSword-2.1.16`）", "由项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 随包分发：解出 `toolkit/AntSword/`（Loader 二进制与源码目录都要在，`toolkit/AntSword/AntSword-Loader-v4.0.3-linux-x64/AntSword` 需可执行）"),
        "netexec" to ToolFix("netexec（nxc）内网横向/协议枚举工具（`toolkit/netexec/`，Kali 上也可 `apt install netexec`）", "`apt install netexec`；本机工具箱版本在 `\$DSH_HOME/redteam/toolkit/netexec/`，缺失就从 项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 解出，或按官方文档用 `pipx install netexec`"),
        "pd-httpx" to ToolFix("ProjectDiscovery httpx 的包装脚本（`~/.local/bin/pd-httpx`）—— 避免与 Python httpx 库的 CLI 撞名", "先跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 装好 `\$DSH_HOME/redteam/toolkit/httpx/httpx`，再重建包装脚本并赋权：`printf '#!/usr/bin/env bash\\nexec \"\${DSH_HOME:-\$HOME/.dsh}/redteam/toolkit/httpx/httpx\" \"\$@\"\\n' > ~/.local/bin/pd-httpx && chmod +x ~/.local/bin/pd-httpx`"),
        "kimi-chrome" to ToolFix("启动「能被 Kimi WebBridge 扩展驱动」的浏览器的启动脚本（`~/.local/bin/kimi-chrome`）", "先装浏览器：`apt install google-chrome-stable`（或 `apt install chromium`）；再建 `~/.local/bin/kimi-chrome`：默认 `exec /usr/bin/google-chrome --no-first-run --no-default-browser-check \"\$@\"`，`KIMI_BROWSER=chromium` 时改用 `/usr/bin/chromium`，开头顺带 `systemctl start kimi-webbridge` 兜底拉起守护进程，最后 `chmod +x ~/.local/bin/kimi-chrome`"),
        "kimi-webbridge" to ToolFix("Kimi WebBridge 本地守护进程（systemd 服务 `kimi-webbridge`，监听 `127.0.0.1:10086`，技能靠它驱动真实浏览器）", "守护进程在 `~/.kimi-webbridge/bin/kimi-webbridge`，由 `/etc/systemd/system/kimi-webbridge.service` 管理：重装后 `sudo systemctl enable --now kimi-webbridge`，再用 `~/.kimi-webbridge/bin/kimi-webbridge status` 确认 `\"running\": true`；浏览器扩展由企业策略从 Chrome 应用商店自动安装（ID `fldmhceldgbpfpkbgopacenieobmligc`），Chrome 137+ 不要走解压加载"),
        "chromium" to ToolFix("Chromium 浏览器（headless 抓渲染后 DOM / 截图 / 生成 PDF 的零依赖方案，`/usr/bin/chromium`）", "`apt install chromium`（也可装 `google-chrome-stable`）；用法：`/usr/bin/chromium --headless=new --no-sandbox --dump-dom <url>`，截图加 `--screenshot=runs/shot.png`"),
        "playwright-cli" to ToolFix("Playwright CLI（需要点击/等待/多标签页时的浏览器自动化，`npx --yes @playwright/cli@latest`）", "`npx --yes @playwright/cli@latest`；浏览器缺了跑 `npx playwright install chromium`；调用时务必加 `--browser chromium`（默认走 chrome 通道会报 `Chromium distribution 'chrome' is not found`）"),
        "nmap" to ToolFix("端口/服务/脚本扫描（Kali 自带，主动扫描主力）", "`apt install nmap`；SYN 扫描（`-sS`）需要 `sudo`，无权限时用 `-sT`"),
        "masscan" to ToolFix("超高速度端口扫描（大网段铺面，噪声大）", "`apt install masscan`；发包需要 root，务必先限定 `--rate` 与授权范围"),
        "nuclei" to ToolFix("模板化漏洞扫描（Nday/1day 检测主力，本机在 `/usr/bin/nuclei`）", "`apt install nuclei`；装完把模板库也补上：`nuclei -update-templates`（模板在 `~/.local/nuclei-templates`）"),
        "sqlmap" to ToolFix("SQL 注入检测与利用", "`apt install sqlmap`"),
        "ffuf" to ToolFix("Web fuzz / 目录爆破（最快最灵活，支持多字典、vhost、参数 fuzz）", "`apt install ffuf`"),
        "feroxbuster" to ToolFix("递归目录爆破（Rust，自动跟随目录层级，目录爆破首选）", "`apt install feroxbuster`"),
        "gobuster" to ToolFix("轻量目录/DNS/vhost 爆破（dir/dns/vhost 三模式）", "`apt install gobuster`"),
        "hydra" to ToolFix("在线弱口令爆破（50+ 协议：SSH/FTP/RDP/SMB/MySQL/MSSQL/Web 表单）", "`apt install hydra`"),
        "john" to ToolFix("John the Ripper 离线哈希破解（CPU 路线，适合小字典/规则）", "`apt install john`；字典缺了先 `apt install wordlists` 再 `gunzip /usr/share/wordlists/rockyou.txt.gz`"),
        "hashcat" to ToolFix("hashcat 离线哈希/口令破解（GPU 优先，CPU 会很慢）", "`apt install hashcat`；先 `hashcat --identify hashes.txt` 确认模式号（如 NTLM `-m 1000`、Kerberoast `-m 13100`）"),
        "wpscan" to ToolFix("WordPress 漏洞与用户枚举扫描", "`apt install wpscan`；需要漏洞库时按提示 `wpscan --update`"),
        "nikto" to ToolFix("Web 服务器配置与已知问题扫描", "`apt install nikto`"),
        "whatweb" to ToolFix("Web 指纹识别（CMS/框架/中间件/版本）", "`apt install whatweb`"),
        "amass" to ToolFix("子域枚举与资产测绘（OWASP Amass）", "`apt install amass`；被动模式 `amass enum -passive -d <domain>`"),
        "theharvester" to ToolFix("公开源邮箱/子域/主机收集（theHarvester）", "`apt install theharvester`"),
        "msfconsole" to ToolFix("Metasploit 框架（`exploit/multi/handler` 接收反弹 Shell、`web_delivery` 投递载荷）", "`apt install metasploit-framework`（提供 `msfconsole`）；首次用先 `msfdb init`"),
        "searchsploit" to ToolFix("本地 Exploit-DB 检索（按组件/版本找公开 POC）", "`apt install exploitdb`（提供 `searchsploit`）；更新库用 `searchsploit -u`"),
        "proxychains4" to ToolFix("把单条命令的 TCP 流量代理进内网（配合 socks5 隧道使用）", "`apt install proxychains4`；只用 `-f` 指向 `runs/` 下的临时配置（如 `proxychains4 -f runs/proxychains-1080.conf ...`），不要改 `/etc/proxychains4.conf`"),
        "socat" to ToolFix("全 TTY 反弹 Shell 监听 / 端口转发（比 nc 稳，可拿 PTY）", "`apt install socat`"),
        "nc" to ToolFix("netcat：反弹 Shell 监听与连通性测试（`nc -lvnp <port>`）", "`apt install netcat-traditional`（提供 `nc`；Debian 系的 `netcat-openbsd` 也可）"),
        "tmux" to ToolFix("会话保持：监听与隧道必须跑在 tmux 里，否则 SSH 断开即丢", "`apt install tmux`；用法：`tmux new -s handler`、`tmux a -t handler`"),
        "impacket" to ToolFix("Impacket 协议套件（PtH/PsExec/WMI/DCOM/Kerberos/凭据转储，Kali 自带 61 个 `impacket-*` 命令）", "`apt install python3-impacket`；常用命令 `impacket-wmiexec` / `impacket-psexec` / `impacket-secretsdump` / `impacket-GetNPUsers`"),
        "enum4linux" to ToolFix("SMB/域环境信息枚举（用户、共享、组、密码策略）", "`apt install enum4linux`（新版另有 `enum4linux-ng`）"),
        "redis-cli" to ToolFix("Redis 客户端（未授权 Redis 的只读探测与写马）", "`apt install redis-tools`（提供 `redis-cli`）"),
        "mysql" to ToolFix("MySQL/MariaDB 客户端（未授权/弱口令 MySQL 连库取数）", "`apt install default-mysql-client`（Kali 上是 `mariadb-client`）"),
    )

    /** 逐技能的额外修复步骤。 */
    val SKILL_FIXES: Map<String, String> = mapOf(
        "active-scan" to "装扫描器：`apt install nmap masscan`；`toolkit/naabu/naabu` 缺就跑 `bash \$DSH_HOME/redteam/setup.sh --yes`。非 root 时 naabu 加 `-scan-type c`，nmap/masscan 的 SYN 扫描必须 `sudo`；开扫前先确认目标在授权范围内。",
        "asset-correlation" to "纯落库/关联技能，不依赖外部工具也不读环境变量；不可用一般是技能文件缺失或插件版本旧 —— 重新安装 redteam 插件后重启 `dsh web`，再确认 `redteam_asset_link` 工具可用。",
        "browser-automation" to "装浏览器：`apt install chromium`（或 `google-chrome-stable`）；playwright 路线再 `npx playwright install chromium`。调用时务必加 `--browser chromium`（默认走 chrome 通道会报 `Chromium distribution 'chrome' is not found`）。",
        "chisel-tunnel" to "补二进制 `toolkit/chisel/chisel`：跑 `bash \$DSH_HOME/redteam/setup.sh --yes`。\n隧道两端要用 VPS：确认 `\$DSH_HOME/.env` 里有 `REDTEAM_VPS_HOST` / `REDTEAM_VPS_USER` / `REDTEAM_VPS_KEY`，命令前 `set -a; . \"\$DSH_HOME/.env\"; set +a`；改完 `.env` 必须重启 `dsh web`，安全组记得放行隧道端口。",
        "cn-proxy-pool" to "只依赖 `curl` / `python3`（缺就 `apt install curl`）；不可用多为技能文件缺失 —— 重新安装插件后重启 `dsh web`。代理只在单条命令上用 `-proxy` / `-x` / `--proxy` 指定，不要动本机系统代理或 `/etc/proxychains4.conf`。",
        "credential-attack" to "`apt install hydra hashcat john`；字典用 `sudo apt install wordlists` 后 `gunzip /usr/share/wordlists/rockyou.txt.gz`。本机无 GPU 时 `hashcat` 很慢，优先小字典 + 规则（`-r /usr/share/hashcat/rules/best64.rule`）。",
        "dir-bruteforce" to "`apt install feroxbuster ffuf gobuster`；`toolkit/dirsearch/dirsearch` 不在 setup.sh 的下载清单里 —— 从 maurosoria/dirsearch 的 GitHub releases 取源码放到 `\$DSH_HOME/redteam/toolkit/dirsearch/` 并保证可执行。",
        "fofa-recon" to "配 `FOFA_KEY`：<https://fofa.info> 登录 → 个人中心 → API Key。\n写进 `\$DSH_HOME/.env`（`FOFA_KEY=你的key`，`chmod 600`）或临时 `export FOFA_KEY=…`，然后重启 `dsh web` 才在本进程生效；自测 `curl -s \"https://fofa.info/api/v1/info/my?key=\$FOFA_KEY\"` 要返回 `\"error\":false`。\n也可以直接跑 `bash \$DSH_HOME/redteam/setup.sh --yes`，由脚本代写并当场校验。",
        "frp-tunnel" to "补 `toolkit/frp/frps`、`toolkit/frp/frpc`：跑 `bash \$DSH_HOME/redteam/setup.sh --yes`（一次解出两个二进制）。\nVPS 侧要 `REDTEAM_VPS_HOST` / `REDTEAM_VPS_KEY`（`\$DSH_HOME/.env`），命令前 `set -a; . \"\$DSH_HOME/.env\"; set +a`，改完重启 `dsh web`，并在云安全组放行 frps 监听端口。",
        "fscan-intranet" to "补 `toolkit/fscan/fscan`：跑 `bash \$DSH_HOME/redteam/setup.sh --yes`；若只下到 `fscan.zip`，手动解压出 `fscan` 并 `chmod +x`。\n技能正文里的 `<你的VPS_IP>` 是占位符：在 `\$DSH_HOME/.env` 写 `REDTEAM_VPS_HOST=<真实IP>` 后重启 `dsh web`，或直接编辑 `\$DSH_HOME/skills/fscan-intranet.md` 把 `<你的VPS_IP>` 换成真实地址（载荷服务：VPS 上跑 `vps.sh serve 9100`）。",
        "gogo-intranet" to "`gogo` setup.sh 不自动下载：从 chainreactors/gogo 的 GitHub releases 按平台取二进制，放到 `\$DSH_HOME/redteam/toolkit/gogo/gogo`（Windows/arm64 变体放同目录）并 `chmod +x`。\n`<你的VPS_IP>` 占位符按 `fscan-intranet` 的办法填：`.env` 里写 `REDTEAM_VPS_HOST` 后重启 `dsh web`，或直接编辑 `\$DSH_HOME/skills/gogo-intranet.md` 替换占位符。",
        "kimi-webbridge" to "浏览器扩展 + 启动器两件事：扩展由企业策略从 Chrome 应用商店自动安装（ID `fldmhceldgbpfpkbgopacenieobmligc`），Chrome 137+ 不要再走 `--load-extension`；没有 Chrome/Chromium 就先 `apt install chromium` 或装 `google-chrome-stable`。\n建启动器 `~/.local/bin/kimi-chrome`（默认起 `/usr/bin/google-chrome`，`KIMI_BROWSER=chromium` 切 Chromium）并 `chmod +x`；守护进程用 `sudo systemctl enable --now kimi-webbridge` 拉起。\n`curl -s http://127.0.0.1:10086/status` 里 `extension_connected` 为 `false` 时，先开浏览器（`kimi-chrome`）再查。",
        "lateral-movement" to "`apt install python3-impacket enum4linux smbclient`（Kali 自带 61 个 `/usr/bin/impacket-*`）；离线破解 `apt install hashcat`。内网目标的所有命令都要走隧道：`proxychains4 -f runs/proxychains-<port>.conf ...`，不要改系统 `proxychains` 配置。",
        "nuclei-scan" to "`apt install nuclei`；模板库 `~/.local/nuclei-templates` 缺了就 `nuclei -update-templates`（跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 也会装）。更新后先 `nuclei -tl` 确认模板库没坏，模板数少于 1000 说明没下全。",
        "passive-recon" to "只依赖 `curl` / `dig` / `jq`：`apt install curl dnsutils jq`；本技能不需要任何 API key。不可用一般是技能文件缺失或插件版本旧 —— 重新安装插件后重启 `dsh web`。",
        "recon-pipeline" to "一次补齐工具箱：跑 `bash \$DSH_HOME/redteam/setup.sh --yes`（subfinder / dnsx / naabu / httpx / ksubdomain 都在里面）。\n`~/.local/bin/pd-httpx` 是指向 `toolkit/httpx/httpx` 的包装脚本，缺了就按 `TOOL_FIXES[\"pd-httpx\"]` 重建；OneForAll 装不上不影响主线，用 subfinder + ksubdomain 替代。",
        "redteam-setup" to "环境脚本在 `\$DSH_HOME/redteam/setup.sh`；没有这个文件说明是 npm 版，去项目 GitHub Release 下载 `dsh-redteam-mode-<版本>-toolkit.tar.gz`，解压后由用户自己执行其中的 README 步骤。\n配完 `FOFA_KEY` 与 VPS 后重启 `dsh web`，并写完成标记：`mkdir -p \"\$DSH_HOME/redteam\" && date -Is > \"\$DSH_HOME/redteam/.setup-complete\"`，最后再跑一次 `redteam_preflight` 复核。",
        "shell-handler" to "本机监听工具：`apt install netcat-traditional socat tmux`（MSF 路线再加 `apt install metasploit-framework`）。\nVPS 变量从 `\$DSH_HOME/.env` 读：命令前 `set -a; . \"\$DSH_HOME/.env\"; set +a`；缺 `REDTEAM_VPS_HOST` 就跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 补，改完 `.env` 必须重启 `dsh web`。监听一律放 tmux 里，别让 SSH 断开把会话带走。",
        "suo5-tunnel" to "补 `toolkit/suo5/suo5-linux-amd64`：跑 `bash \$DSH_HOME/redteam/setup.sh --yes`（或从 zema1/suo5 的 GitHub releases 取同名二进制）并 `chmod +x`。\n隧道出口/中转依赖 VPS：`\$DSH_HOME/.env` 里配 `REDTEAM_VPS_HOST` / `REDTEAM_VPS_KEY` 后重启 `dsh web`；隧道入口的 WebShell 必须是冰蝎马/哥斯拉马（见技能 `webshell-toolkit`），否则用户无法复用。",
        "unauth-exploit" to "装客户端：`apt install redis-tools default-mysql-client python3-impacket`（MSSQL 用 `impacket-mssqlclient`）。\n内网目标要隧道：`apt install proxychains4` 后用 `-f runs/proxychains-<port>.conf`，或先按技能 `suo5-tunnel` 建好 socks5；`REDTEAM_VPS_HOST` 没配时依赖 VPS 的步骤会被判不可用。",
        "vps-reverse-shell" to "填 VPS：跑 `bash \$DSH_HOME/redteam/setup.sh --yes` 引导写入 `REDTEAM_VPS_HOST`（`用户@主机`）与 `REDTEAM_VPS_KEY`（私钥路径，默认 `\$DSH_HOME/redteam/toolkit/vps/id_rsa`）。\n也可以手动写 `\$DSH_HOME/.env` 后重启 `dsh web`，或直接编辑 `\$DSH_HOME/skills/vps-reverse-shell.md`，把 `<你的VPS_IP>` / `<VPS 主机名>` 换成真实地址。\n私钥必须 `chmod 600`，连通性用 `ssh -i \$DSH_HOME/redteam/toolkit/vps/id_rsa -o BatchMode=yes <user>@<ip> 'echo ok'` 验；安全组放行 22 与 9000-9999。",
        "web-fingerprint" to "`~/.local/bin/pd-httpx` 缺了就按 `TOOL_FIXES[\"pd-httpx\"]` 重建（它 exec `toolkit/httpx/httpx`）；`nuclei` 缺就 `apt install nuclei`，技术识别模板库用 `nuclei -update-templates` 补。",
        "webshell-toolkit" to "`toolkit/Behinder`、`toolkit/Godzilla`、`toolkit/AntSword` 由项目 GitHub Release 里的 `dsh-redteam-mode-<版本>-toolkit.tar.gz` 随包分发（setup.sh 不下这三样）：下载解压后解出对应目录即可。\nGUI 需要 Java 与图形会话：`java -version` 确认（缺就 `apt install openjdk-17-jre`）、`DISPLAY=:10.0`；无 GUI 时用 `sed` 换密钥直接生成冰蝎马（模板在 `toolkit/Behinder/server/`）。",
    )

    /** 环境变量怎么拿（拿不准的就不编，直接让用户填值）。 */
    val ENV_HINTS: Map<String, String> = mapOf(
        "FOFA_KEY" to "在 <https://fofa.info> 登录 → 个人中心 → API Key 取；自测 `curl -s \"https://fofa.info/api/v1/info/my?key=\$FOFA_KEY\"` 返回 `\"error\":false` 即有效",
        "REDTEAM_VPS_HOST" to "填 VPS 登录地址 `用户@主机`（例如 `export REDTEAM_VPS_HOST=ubuntu@203.0.113.10`）",
        "REDTEAM_VPS_USER" to "填 VPS 登录用户（默认 `ubuntu`）",
        "REDTEAM_VPS_KEY" to "填 VPS 私钥路径（默认 `\$DSH_HOME/redteam/toolkit/vps/id_rsa`，权限必须 `chmod 600`）",
    )

    /** 工具别名规范化：`impacket-wmiexec` 这类命令归到 `impacket` 这一条修法上。 */
    fun canonicalTool(name: String): String = when {
        name.startsWith("impacket-") -> "impacket"
        name.startsWith("enum4linux-") -> "enum4linux"
        else -> name
    }
}
