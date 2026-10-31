package top.tianyan.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.ApprovalMode

class ApprovalPolicyEngineTest {
    private val policy = ApprovalPolicyEngine(HarnessPathResolver())
    private val workspace = "/workspace/project"

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `request mode allows read but gates state changing and external tools`() {
        assertFalse(policy.decide(ApprovalMode.REQUEST, HarnessTool.READ, args("path" to "a.txt"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.WRITE, args("path" to "a.txt", "content" to "x"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.BASE, args("command" to "git status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.REQUEST, HarnessTool.PROCESS, args("action" to "status", "id" to "server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.PROCESS, args("action" to "start", "id" to "server", "command" to "python -m http.server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.MCP, args("name" to "search"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.DOWNLOAD, args("destination" to "a.zip"), workspace).required)
    }

    @Test
    fun `assisted mode allows workspace edits and routine inspection or verification`() {
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "src/Main.kt", "content" to "x"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.EDIT, args("path" to "/workspace/project/src/Main.kt"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "./gradlew test"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.PROCESS, args("action" to "logs", "id" to "server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.PROCESS, args("action" to "stop", "id" to "server"), workspace).required)
    }

    @Test
    fun `assisted mode gates outside workspace writes and external or risky commands`() {
        val outside = policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "/etc/hosts", "content" to "x"), workspace)
        assertTrue(outside.required)
        assertEquals("high", outside.riskLevel)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "/workspace/other/file.txt", "content" to "x"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "../other/file.txt", "content" to "x"), workspace).required)

        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rm -rf /"), workspace).required)
        assertEquals("critical", policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rm -rf /"), workspace).riskLevel)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "curl https://example.com"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "apt-get install ripgrep"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git push"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status; rm -rf build"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "cat README.md > backup.txt"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . -delete"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.MCP, args("name" to "query"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.DOWNLOAD, args("destination" to "a.zip"), workspace).required)
    }

    @Test
    fun `assisted mode auto allows safe read pipelines and cd then inspect`() {
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "ls -la /workspace 2>&1 | head -40"),
                workspace,
            ).required,
        )
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cd /workspace/RelayGo && rg -n foo lib | head -40"),
                workspace,
            ).required,
        )
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "git status | head -20"),
                workspace,
            ).required,
        )
        // Still gate unsafe composition
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "ls /workspace | rm -rf /"),
                workspace,
            ).required,
        )
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cd /workspace && curl https://example.com"),
                workspace,
            ).required,
        )
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat README.md > backup.txt"),
                workspace,
            ).required,
        )
    }

    @Test
    fun `full access bypasses approval for every tool`() {
        val tools = HarnessTool.entries
        tools.forEach { tool ->
            val toolArgs = when (tool) {
                HarnessTool.WRITE, HarnessTool.EDIT -> args("path" to "/etc/hosts", "content" to "x")
                HarnessTool.BASE -> args("command" to "rm -rf /")
                HarnessTool.PROCESS -> args("action" to "start", "id" to "server", "command" to "rm -rf /")
                HarnessTool.DOWNLOAD -> args("url" to "https://example.com/a.zip", "destination" to "a.zip")
                HarnessTool.READ -> args("path" to "/etc/passwd")
                HarnessTool.HOST -> args("action" to "exec", "command" to "pm uninstall --user 0 package")
                else -> args("name" to "anything")
            }
            assertFalse("$tool should be unrestricted in FULL_ACCESS", policy.decide(ApprovalMode.FULL_ACCESS, tool, toolArgs, workspace).required)
        }
    }

    @Test
    fun `host status is readable but host exec requires critical approval in assisted mode`() {
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "settings_get"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "package_list"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "logcat"), workspace).required)
        val decision = policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "exec", "command" to "settings put global foo 1"), workspace)
        assertTrue(decision.required)
        assertEquals("critical", decision.riskLevel)
        assertEquals("high", policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "settings_put"), workspace).riskLevel)
        assertEquals("critical", policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "package_uninstall_user"), workspace).riskLevel)
    }

    @Test
    fun `assisted mode auto allows host GUI observe and touch primitives`() {
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_observe"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_capture", "path" to "/tmp/a.png"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_click", "x" to "1", "y" to "2"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_swipe", "x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_input_text", "text" to "hi"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "screen_key", "key" to "back"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "app_launch", "package" to "com.tencent.mobileqq"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "virtual_screen_click", "x" to "500", "y" to "400"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "virtual_screen_input_text", "text" to "今天发新版本"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "virtual_screen_screenshot"), workspace).required)
    }

    @Test
    fun `request mode still gates host GUI touch actions`() {
        assertFalse(policy.decide(ApprovalMode.REQUEST, HarnessTool.HOST, args("action" to "screen_observe"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.HOST, args("action" to "screen_click", "x" to "1", "y" to "2"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.HOST, args("action" to "app_launch", "package" to "com.android.settings"), workspace).required)
    }

    @Test
    fun `create request binds operation args hash and expiry`() {
        val toolCall = ToolCall(
            id = "call-1",
            createdAt = 1L,
            tool = HarnessTool.BASE,
            args = args("command" to "rm -rf build"),
            rawToolName = "base",
        )
        val before = System.currentTimeMillis()
        val request = policy.createRequest(
            sessionId = "session-1",
            toolCall = toolCall,
            workspace = workspace,
            decision = ApprovalDecision(required = true, riskLevel = "high", reason = "r", summary = "s"),
            operationId = "op-9",
        )

        assertEquals("op-9", request.operationId)
        assertEquals(ApprovalPolicyEngine.argsHash(toolCall.args.toString()), request.argsHash)
        assertEquals(request.createdAt + ApprovalPolicyEngine.APPROVAL_TTL_MS, request.expiresAt)
        assertTrue("审批有效期应为正数", request.expiresAt > before)
    }

    @Test
    fun `build script tool approval policy`() {
        // list and get are read-only and bypass approval in ASSISTED mode
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "list"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "get", "id" to "builtin-android"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "  List "), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "GET", "id" to "builtin-android"), workspace).required)

        // Mutating actions require approval in ASSISTED mode
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "create", "name" to "custom"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "bind", "id" to "1"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "unbind"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "delete", "id" to "1"), workspace).required)
    }

    @Test
    fun `dynamic shell syntax requires approval even with routine command prefix`() {
        // Command substitution hidden behind innocent ls
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "ls \$(rm -rf /tmp)"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status `whoami`"), workspace).required)
        // Nested shell execution
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "sh -c 'rm -rf /'"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "bash -c 'curl bad.com'"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "eval ls"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "source ./script.sh"), workspace).required)
    }

    @Test
    fun `executable or writable filter features require approval`() {
        // awk 是编程语言：system() 可执行任意命令，曾被过滤段白名单放行
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "ls -la | awk 'BEGIN{system(\"touch /data/local/tmp/pwned\")}'"),
                workspace,
            ).required,
        )
        // awk 的管道协程同样可执行外部命令
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat app.log | awk '{print \$1 | \"mail root\"}'"),
                workspace,
            ).required,
        )
        // sort -o 可写任意路径（工作区外）
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "ls | sort -o /data/local/tmp/poc"),
                workspace,
            ).required,
        )
        // find 的落盘原语（-fls / -fprint / -fprintf）
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . -fls /data/local/tmp/poc"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . -name '*.kt' -fprintf /data/local/tmp/poc \"x\""), workspace).required)
    }

    @Test
    fun `rg preprocessor flag requires approval in primary and quoted concatenation forms`() {
        // rg --pre 以任意命令作为预处理器
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "rg --pre 'sh /data/local/tmp/evil.sh' pattern ."),
                workspace,
            ).required,
        )
        // 引号拼接形态："--""pre" 在原文本上匹配不到 --pre，shell 却会拼接还原
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "rg \"--\"\"pre\" 'sh /data/local/tmp/evil.sh' pattern ."),
                workspace,
            ).required,
        )
    }

    @Test
    fun `quoted or backslash-escaped flags cannot bypass the blacklists`() {
        // shell 剥引号后 find 收到 -exec：原文上旗标前是引号不是空白，原文正则匹配不到
        assertTrue(
            policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . \"-exec\" rm {} +"), workspace).required,
        )
        // 反斜杠转义：\r → r、\s → s，argv 实为 --pre / -fls
        assertTrue(
            policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rg --p\\re 'sh /data/local/tmp/evil.sh' pattern ."), workspace).required,
        )
        assertTrue(
            policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . -fl\\s /data/local/tmp/poc"), workspace).required,
        )
        // 正向对照：--pre-glob 是无害旗标，不得被 --pre 规则误伤
        assertFalse(
            policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rg --pre-glob '*.md' pattern ."), workspace).required,
        )
    }

    @Test
    fun `plain read-only pipelines stay auto approved after filter hardening`() {
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat build.log | grep -c error | head -5"),
                workspace,
            ).required,
        )
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "grep -o 'https?://[^ ]*' README.md | head -10"),
                workspace,
            ).required,
        )
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cd /workspace/RelayGo && rg -n foo lib | head -40"),
                workspace,
            ).required,
        )
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "ls -la /workspace 2>&1 | cut -d' ' -f1 | uniq"),
                workspace,
            ).required,
        )
    }

    @Test
    fun `bare ampersand background composition requires approval`() {
        // `&` 后台执行符串联的第二段命令完全绕过前缀白名单，必须审批
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat pom.xml & rm build/output.apk"),
                workspace,
            ).required,
        )
        // 后半段即使是无害白名单命令，裸 `&` 本身也超出管线白名单的表达范围
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat pom.xml & ls"),
                workspace,
            ).required,
        )
        // fd 重定向不能掩护裸 `&`
        assertTrue(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat pom.xml 2>&1 & rm build/output.apk"),
                workspace,
            ).required,
        )
        // 对照：`2>&1 | head` 与 `cd x && rg` 合法组合不受影响
        assertFalse(
            policy.decide(
                ApprovalMode.ASSISTED,
                HarnessTool.BASE,
                args("command" to "cat build.log 2>&1 | head -5"),
                workspace,
            ).required,
        )
    }

    @Test
    fun `mutation commands as bare primaries require approval even with routine prefix`() {
        // rm/mv/cp/tee 加入黑名单后的纵深防御：即使前缀锚定命中也不放行
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "cat pom.xml | tee /etc/hosts"), workspace).required)
    }
}

