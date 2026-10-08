package top.tianyan.app.core.model

/**
 * 作战阶段：信息收集 → 互联网权限 → 边界突破 → 内网权限 → 靶标权限。
 * 移植自上游 `store-core.js` 的 `DEFAULT_STAGES` / `POINT_STAGE_OVERRIDE` / `scoreStageOf`。
 *
 * 得分必须按这五个阶段分桶——演练方看报告就是按这条推进线读的，
 * 一条「内网拿到域管」的得分落在「互联网资产权限」桶里，整份报告的推进叙事就废了。
 */
object RedTeamStage {

    /** 阶段允许的 code —— 报告与攻击链页面只认这 5 个，其它值分不了桶。 */
    val VALID_STAGE_CODES = listOf("recon", "internet", "boundary", "internal", "target")

    data class Section(val label: String, val items: List<String>)

    data class Stage(
        val code: String,
        val name: String,
        val subtitle: String,
        val color: String,
        /** 0 = 前置阶段（不计分），1 = 计分阶段。 */
        val scored: Int,
        val goal: String,
        val sections: List<Section>,
        val tools: String,
        /** 转入下一阶段的提示；最后一个阶段为空。 */
        val transition: String,
    )

    val DEFAULT_STAGES: List<Stage> = listOf(
        Stage(
            code = "recon", name = "信息收集", subtitle = "RECON · 互联网侧", color = "#06b6d4", scored = 0,
            goal = "在互联网侧展开信息收集，确定值得打的资产面",
            sections = listOf(
                Section("资产测绘", listOf("子域名 / C 段 / 端口指纹", "FOFA、被动 DNS、证书透明、主动扫描")),
                Section("攻击面确认", listOf("Web 标题与指纹、暴露的服务与管理端口", "归属、WAF / CDN 识别")),
                Section("攻击面排序", listOf("按易打性与预期得分排序", "确定先打哪几台")),
            ),
            tools = "ARL / 灯塔、fscan、gogo、OneForAll、ENScan、Goby、Nmap、Burp、Nuclei",
            transition = "确认互联网攻击面，转入利用",
        ),
        Stage(
            code = "internet", name = "互联网资产权限", subtitle = "INTERNET-SIDE PRIVILEGE", color = "#ef4444", scored = 1,
            goal = "在互联网侧资产上拿到账号、权限等得分",
            sections = listOf(
                Section("拿账号", listOf("弱口令 / 越权 / 未授权 / 逻辑漏洞", "后台管理员、普通账号")),
                Section("拿权限", listOf("Nday / 1day RCE、文件上传、反序列化", "WebShell、命令执行、服务器权限")),
                Section("拿数据", listOf("数据库、配置泄露、批量敏感信息")),
            ),
            tools = "sqlmap、Nuclei、冰蝎 / 哥斯拉 / 蚁剑、fscan、Burp、自定义 POC",
            transition = "用已控主机打通出网通道",
        ),
        Stage(
            code = "boundary", name = "边界突破", subtitle = "BOUNDARY BREACH · TUNNEL", color = "#f59e0b", scored = 1,
            goal = "成功搭建隧道，把控制能力延伸进内网",
            sections = listOf(
                Section("隧道", listOf("suo5 / frp / Stowaway / Neo-reGeorg / Venom", "socks5 落地、多级级联")),
                Section("出网通道", listOf("域名前置、CDN 隐藏、云函数转发", "DNS / ICMP / 443 隐蔽信道")),
            ),
            tools = "suo5、frp、Stowaway、Neo-reGeorg、GOST、proxychains、chisel",
            transition = "隧道就绪，转入内网",
        ),
        Stage(
            code = "internal", name = "内网资产权限", subtitle = "INTERNAL-SIDE PRIVILEGE", color = "#8b5cf6", scored = 1,
            goal = "通过隧道在内网资产上拿分",
            sections = listOf(
                Section("内网测绘", listOf("存活 / 端口 / 服务 / 共享目录", "数据库、中间件、备份系统")),
                Section("横向与提权", listOf("凭据复用、Pass-the-Hash、票据", "PsExec / WMIExec / SSH / RDP")),
                Section("数据", listOf("批量导出敏感信息，落 runs/ 并记引用")),
            ),
            tools = "gogo / fscan（走隧道）、Impacket、Mimikatz / LaZagne、BloodHound、suo5",
            transition = "定位内网核心靶标",
        ),
        Stage(
            code = "target", name = "靶标权限", subtitle = "TARGET SYSTEM", color = "#10b981", scored = 1,
            goal = "获取内网重要资产（靶标）的权限",
            sections = listOf(
                Section("靶标定位", listOf("按演练规则确认靶标系统 / 服务器范围", "明确得分判定口径")),
                Section("拿靶标", listOf("root / SYSTEM / 管理员 / 云 AK", "业务数据读写与配置变更能力")),
                Section("成果固化", listOf("证据链归档（配置 / 数据 / 主机信息 / 截图）", "攻击时间线回顾")),
            ),
            tools = "凭据复用、内网横向工具、证据链归档与报告",
            transition = "",
        ),
    )

    /**
     * 特殊得分点 → 固定阶段（优先级高于按资产内外网推导）。
     *
     * 按《突破入侵类得分规则》的规则号判阶段：规则 5/6/7（邮箱系统 / 办公业务系统 / 集权系统）
     * 就是演练的「靶标」，拿到即算打到核心目标，归入靶标权限；规则 22-25（突破网络边界）
     * 归入边界突破。`core-system` 是老 code 的兜底，老数据仍可能有。
     */
    val POINT_STAGE_OVERRIDE: Map<String, String> = mapOf(
        "core-system" to "target",
        "boundary" to "boundary",
        "mail-admin" to "target", "mail-user" to "target",
        "biz-admin" to "target", "biz-user" to "target",
        "central-admin" to "target", "central-user" to "target", "central-managed" to "target",
        "web-app" to "target", "central-system" to "target",
        "boundary-logical" to "boundary", "boundary-strong" to "boundary",
        "boundary-physical" to "boundary", "boundary-supply" to "boundary",
    )

    /** 旧攻击链 stage → 新阶段 code 的兜底映射。 */
    val LEGACY_STAGE_MAP: Map<String, String> = mapOf(
        "recon" to "recon", "vuln" to "internet", "exploit" to "internet",
        "access" to "internal", "pivot" to "boundary", "data" to "internal", "other" to "recon",
    )

    /** 自动推导一条得分属于哪个阶段（显式 stage_code > 类型特判 > 资产内外网 > target 地址）。 */
    fun scoreStageOf(stageCode: String?, hitCode: String?, assetScope: String?, target: String?): String {
        if (!stageCode.isNullOrBlank()) return stageCode
        POINT_STAGE_OVERRIDE[hitCode]?.let { return it }
        if (assetScope == "internal") return "internal"
        if (assetScope == "external") return "internet"
        val raw = target.orEmpty()
        val match = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""").find(raw)?.groupValues?.get(1)
        if (match != null) return if (RedTeamIpUtils.scopeOfIp(match) == "internal") "internal" else "internet"
        return "internet"
    }

    /**
     * 攻击链步骤的阶段解析。
     *
     * 只有 5 个 code 会被分桶；其它值（尤其外部工具常见的 external / foothold / tunnel / privilege）
     * 写了等于步骤不落在任何阶段，所以**退回按老 stage 兜底并显式告警**，而不是静默丢桶。
     */
    data class ChainStage(val code: String, val warning: String? = null)

    fun resolveChainStage(stageCode: String?, legacyStage: String?): ChainStage {
        val legacy = legacyStage.orEmpty().ifBlank { "other" }
        val wanted = stageCode?.trim().orEmpty()
        if (wanted.isNotEmpty()) {
            return if (wanted in VALID_STAGE_CODES) {
                ChainStage(wanted)
            } else {
                val code = LEGACY_STAGE_MAP[legacy] ?: "recon"
                ChainStage(
                    code,
                    "无效的 stage_code=\"$wanted\"（已忽略）：只接受 ${VALID_STAGE_CODES.joinToString("/")}" +
                        "；本步按 stage 兜底落到 $code。",
                )
            }
        }
        return ChainStage(LEGACY_STAGE_MAP[legacy] ?: "recon")
    }

    /**
     * 目标键归一化，移植自上游 `targetKey`：URL 取 scheme 前缀，否则取首个词元。
     * 复现与证据按它归组——同一个目标的写法不同（带路径 / 不带）必须落到同一个键上。
     */
    fun targetKey(target: String?, fallbackIp: String?): String {
        val t = target?.trim().orEmpty()
        if (t.isNotEmpty()) {
            Regex("""^([a-z][a-z0-9+.-]*://[^/?#\s]+)""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                ?.let { return it }
            Regex("""^([^\s/?#]+)""").find(t)?.groupValues?.get(1)?.let { return it }
            return t
        }
        return fallbackIp.takeIf { !it.isNullOrBlank() } ?: "(未指定目标)"
    }
}