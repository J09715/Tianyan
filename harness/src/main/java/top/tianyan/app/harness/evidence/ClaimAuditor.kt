package top.tianyan.app.harness.evidence

/**
 * 完成声称审计器：闸门只能看工具结果，管不住模型在正文里写「所有测试通过」。
 *
 * 这是最后一道防线——它读的是模型的自然语言收尾，对照证据账本里的改动与验证事实。
 * 三条硬约束（都是踩出来的）：
 * 1. 只有真的改过代码才审计。纯报告/纯问答交付没有「陈旧的绿」可言，
 *    在这里拦截只会制造大量误报，把真警报淹没在噪音里。
 * 2. 只认晚于最后一次改动的通过记录。改动前的绿证明的是旧代码，拿它背书新代码就是造假。
 * 3. 验证形态要匹配：类型检查不能替测试背书，反之亦然；只有命令完全无法归类时才放行。
 */
object ClaimAuditor {

    data class AuditResult(val blocked: Boolean, val warning: Boolean, val message: String)

    /** 测试通过类声称（中英双语）。 */
    private val TEST_CLAIM = Regex(
        """全绿|所有测试通过|\btests?\s+(?:pass(?:ed|ing)?|green)\b|\d+\s*/\s*\d+\s*(?:通过|passed|pass)""",
        RegexOption.IGNORE_CASE,
    )

    /** 类型检查通过类声称。 */
    private val TYPECHECK_CLAIM = Regex(
        """(?:typecheck|类型检查)\s*(?:干净|clean|passed|通过)""",
        RegexOption.IGNORE_CASE,
    )

    /** 从声称文本里抽取 N/N 计数（带分组，用于比对）。 */
    private val COUNT_IN_CLAIM = Regex(
        """(\d+)\s*/\s*(\d+)\s*(?:通过|passed|pass|全绿)""",
        RegexOption.IGNORE_CASE,
    )

    /** 从验证记录里抽取 N/N 计数；分母语义由验证命令自报，这里只做形如 12/12 的解析。 */
    private val COUNT_IN_EVIDENCE = Regex(
        """(\d+)\s*/\s*(\d+)\s*(?:通过|passed|pass|全绿|tests?|checks?|suites?|specs?|cases?)""",
        RegexOption.IGNORE_CASE,
    )

    private val TYPECHECK_COMMAND = Regex(
        """\btsc\b|typecheck|type-check|类型检查|\bmypy\b|\bpyright\b|dart\s+analyze|flutter\s+analyze""",
        RegexOption.IGNORE_CASE,
    )

    private val TEST_COMMAND = Regex(
        // ASCII 字母前的负向断言是必要的：`latest` 里含 "test"，
        // 而 `:app:testDebugUnitTest` 这类 Gradle 任务名后面的 "D" 又让 `\btest\b` 失配。
        // 用「前面不是字母」而不是「后面是边界」，两种真实命令形态都能归到 test。
        """(?<![A-Za-z])tests?|unittest|\bpytest\b|\bjest\b|\bvitest\b|\bmocha\b|\bphpunit\b|\brspec\b|\bgotestsum\b|\bgo\s+test\b|\bcargo\s+test\b|ctest""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 验证命令归类：test / typecheck / unknown。
     * 先判类型检查：`tsc` / `mypy` 这类更具体，且不会被 test 关键词误吞。
     */
    fun verificationKind(command: String): String {
        val normalized = command.trim()
        if (normalized.isEmpty()) return "unknown"
        if (TYPECHECK_COMMAND.containsMatchIn(normalized)) return "typecheck"
        if (TEST_COMMAND.containsMatchIn(normalized)) return "test"
        return "unknown"
    }

    fun audit(claimText: String, state: EvidenceState): AuditResult {
        val claimsTest = TEST_CLAIM.containsMatchIn(claimText)
        val claimsTypecheck = TYPECHECK_CLAIM.containsMatchIn(claimText)
        // 规则 1：没有成功/类型检查类声称，就没什么可审的。
        if (!claimsTest && !claimsTypecheck) return AuditResult(false, false, "")

        // 规则 2：没改过代码就不审。文档/报告交付里出现的「全绿」通常是转述，不是自吹。
        // BREAK2: rule 2 disabled
        // if (state.filesModified.isEmpty()) return AuditResult(false, false, "")

        val fresh = state.verifications.filter { it.at >= state.lastMutationAt }
        val freshPassing = fresh.filter { it.passed }

        if (claimsTest && !backed("test", freshPassing)) return blocked("test", fresh, freshPassing)
        if (claimsTypecheck && !backed("typecheck", freshPassing)) return blocked("typecheck", fresh, freshPassing)

        // 规则 5：计数对不上只给警告，不拦。数字口径差异（用例数 vs 文件数）不足以否决交付。
        val claimedCount = parseCount(COUNT_IN_CLAIM, claimText)
        val newest = freshPassing.lastOrNull()
        if (claimedCount != null && newest != null) {
            val recordedCount = parseCount(COUNT_IN_EVIDENCE, newest.summary)
                ?: parseCount(COUNT_IN_EVIDENCE, newest.command)
            if (recordedCount != null && claimedCount != recordedCount) {
                return AuditResult(
                    blocked = false,
                    warning = true,
                    message = "声称的通过计数 ${claimedCount.first}/${claimedCount.second} 与最近一次验证记录的 " +
                        "${recordedCount.first}/${recordedCount.second} 不一致（命令：${newest.command}）。" +
                        "请核对统计口径，或直接引用验证工具的真实输出。",
                )
            }
        }
        return AuditResult(false, false, "")
    }

    /** 新鲜通过记录里，形态匹配（或完全无法归类）的验证才能给该声称背书。 */
    private fun backed(expectedKind: String, freshPassing: List<VerificationRecord>): Boolean =
        freshPassing.any {
            val kind = verificationKind(it.command)
            kind == expectedKind || kind == "unknown"
        }

    private fun blocked(
        expectedKind: String,
        fresh: List<VerificationRecord>,
        freshPassing: List<VerificationRecord>,
    ): AuditResult {
        val claimLabel = if (expectedKind == "test") "测试通过" else "类型检查通过"
        val newestPassing = freshPassing.lastOrNull()
        val newestFailing = fresh.lastOrNull { !it.passed }
        val detail = when {
            // 有新鲜通过记录却仍然不匹配 → 只可能是形态错配。
            newestPassing != null -> {
                val actualLabel = if (verificationKind(newestPassing.command) == "typecheck") "类型检查" else "测试"
                "晚于最后一次改动的通过验证是「$actualLabel」（${newestPassing.command}），" +
                    "它与「$claimLabel」不是同一类验证，不能互相背书。"
            }
            newestFailing != null ->
                "晚于最后一次改动的验证是失败的（${newestFailing.command}），失败结果不能拿来声称「$claimLabel」。"
            else ->
                "没有任何晚于最后一次代码改动的验证记录；最后一次改动之前的绿色结果只证明旧代码，" +
                    "那是陈旧的绿，不能替本次改动背书。"
        }
        return AuditResult(
            blocked = true,
            warning = false,
            message = "检测到完成声称「$claimLabel」，但$detail" +
                "请先用 verify 工具运行对应验证并确认通过，或如实说明该结论未经验证。",
        )
    }

    private fun parseCount(pattern: Regex, text: String): Pair<Int, Int>? {
        val match = pattern.find(text) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val right = match.groupValues[2].toIntOrNull() ?: return null
        return left to right
    }
}
