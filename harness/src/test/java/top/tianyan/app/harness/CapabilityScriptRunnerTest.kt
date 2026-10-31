package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.directory.CapabilityScriptRunner
import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException

/**
 * codemode 脚本运行器合同：编排价值（批量/循环合并轮次）与安全边界
 * （Java 全禁、deadline 熔断、内层调用照常走审批与留痕）同时锁定。
 */
class CapabilityScriptRunnerTest {

    private fun runner(
        redactor: (String) -> String = { it },
        inner: suspend (String, String, JsonObject) -> Pair<Boolean, String> = { _, _, _ -> true to "ok" },
    ) = CapabilityScriptRunner(redactor, inner)

    @Test
    fun `script orchestrates multiple capability calls in one round`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val runner = runner(inner = { server, tool, _ ->
            calls += server to tool
            true to "结果-$tool"
        })
        val code = """
            var a = capability.call("host", "virtual_screen_wait", {duration_ms: 0});
            var b = capability.call("host", "virtual_screen_close", {});
            JSON.stringify({first: a.output, second: b.output, allOk: a.ok && b.ok})
        """.trimIndent()

        val (ok, output) = runner.execute(code, 10_000)

        assertTrue(ok)
        assertEquals(listOf("host" to "virtual_screen_wait", "host" to "virtual_screen_close"), calls)
        assertTrue(output.contains("结果-virtual_screen_wait"))
        assertTrue(output.contains("结果-virtual_screen_close"))
        assertTrue(output.contains("allOk\":true"))
    }

    @Test
    fun `class shutter denies java interop entirely`() = runBlocking {
        val (ok, output) = runner().execute("java.lang.Runtime.getRuntime().exec('id');", 10_000)

        assertFalse("脚本绝不能触达 Java 运行时", ok)
        assertTrue(output.contains("脚本执行失败"))
    }

    @Test
    fun `deadline interrupts infinite loops`() = runBlocking {
        val started = System.currentTimeMillis()
        val (ok, output) = runner().execute("while(true){}", 1_500)

        assertFalse(ok)
        assertTrue(output.contains("超时"))
        assertTrue("deadline 必须熔断而不是挂死", System.currentTimeMillis() - started < 15_000)
    }

    @Test
    fun `blocked inner call surfaces ok=false with guidance`() = runBlocking {
        val runner = runner(inner = { _, tool, _ ->
            if (tool == "screen_click") false to "宿主能力域没有工具「screen_click」。请直接调用 host 工具。" else true to "ok"
        })
        val (ok, output) = runner.execute(
            "var r = capability.call(\"host\", \"screen_click\", {x:1,y:2}); r.ok + \":\" + r.output",
            10_000,
        )

        assertTrue("被拒绝的内层调用不是脚本失败，脚本能感知并改道", ok)
        assertTrue(output.startsWith("false:"))
        assertTrue(output.contains("host 工具"))
    }

    @Test
    fun `inner errors are visible to the script`() = runBlocking {
        val runner = runner(inner = { _, _, _ -> false to "MCP[server] 连接失败" })
        val (ok, output) = runner.execute(
            "var results = []; for (var i = 0; i < 3; i++) { var r = capability.call(\"srv\", \"tool\" + i, {}); results.push(r.ok); } results.join(\",\")",
            10_000,
        )

        assertTrue(ok)
        assertEquals("false,false,false", output)
    }

    @Test
    fun `oversized code is rejected without execution`() = runBlocking {
        val big = "x".repeat(CapabilityScriptRunner.MAX_CODE_CHARS + 1)
        val (ok, output) = runner().execute(big, 10_000)

        assertFalse(ok)
        assertTrue(output.contains("脚本过长"))
    }

    @Test
    fun `bare completion values 1 and array use the kotlin json path`() = runBlocking {
        val (numberOk, numberOut) = runner().execute("1", 10_000)
        assertTrue(numberOut, numberOk)
        assertEquals("1", numberOut)

        val (arrayOk, arrayOut) = runner().execute("[1, 2, 3]", 10_000)
        assertTrue(arrayOut, arrayOk)
        assertEquals("[1,2,3]", arrayOut)

        // NativeJSON.stringify 会调用 toJSON；Kotlin 序列化按元素展开。
        // 桌面 JVM 有 java.beans，两条路径对普通对象输出相同，这条断言才能抓住 stringify 回归。
        val (lockedOk, lockedOut) = runner().execute(
            "var values = [1, 2, 3]; values.toJSON = function () { return 'native-json'; }; values",
            10_000,
        )
        assertTrue(lockedOut, lockedOk)
        assertEquals("[1,2,3]", lockedOut)

        val (stringifiedOk, stringifiedOut) = runner().execute(
            "var values = [1, 2, 3]; values.toJSON = function () { return 'native-json'; }; JSON.stringify(values)",
            10_000,
        )
        assertTrue(stringifiedOut, stringifiedOk)
        assertEquals("[1,2,3]", stringifiedOut)
    }

    @Test
    fun `sparse array completion values encode holes as null`() = runBlocking {
        val (literalOk, literalOut) = runner().execute("[1,,3]", 10_000)
        assertTrue(literalOut, literalOk)
        assertEquals("[1,null,3]", literalOut)

        val (deletedOk, deletedOut) = runner().execute("var values = [1, 2, 3]; delete values[1]; values", 10_000)
        assertTrue(deletedOut, deletedOk)
        assertEquals("[1,null,3]", deletedOut)
    }

    @Test
    fun `result objects are json stringified`() = runBlocking {
        val runner = runner()
        val (ok, output) = runner.execute(
            "var a = capability.call(\"host\", \"virtual_screen_close\", {}); ({done: a.ok, via: \"script\"})",
            10_000,
        )

        assertTrue(ok)
        assertTrue(output.contains("\"done\":true"))
        assertTrue(output.contains("\"via\":\"script\""))
    }

    @Test
    fun `android context rejects Java JSON conversion while preserving native JS data`() = runBlocking {
        val runner = runner(inner = { _, _, args ->
            val context = Context.getCurrentContext()
            val failure = runCatching { context.javaToJSONConverter.apply(Any()) }.exceptionOrNull()
            assertTrue(failure is EvaluatorException)
            assertTrue(failure!!.message.orEmpty().contains("Java object JSON conversion is disabled"))
            assertEquals("{\"items\":[1,true,null,{\"text\":\"天衍\"}]}", args.toString())
            true to "ok"
        })

        val (ok, output) = runner.execute(
            "var data = {items: [1, true, null, {text: '天衍'}]}; capability.call('host', 'test', data); data;",
            10_000,
        )

        assertTrue(output, ok)
        assertEquals("{\"items\":[1,true,null,{\"text\":\"天衍\"}]}", output)
    }

    @Test
    fun `parent cancellation cancels inner call and prevents later dispatch even inside JS catch`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val running = async {
            runner(inner = { _, _, _ ->
                calls.incrementAndGet()
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }).execute("try { capability.call('host','first',{}); } catch(e) {} capability.call('host','second',{});", 10_000)
        }
        withTimeout(5_000) { entered.await() }
        running.cancel()
        withTimeout(5_000) { cancelled.await(); running.cancelAndJoin() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `deadline cancels suspended inner call and cannot be swallowed by JS`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val started = System.nanoTime()
        val (ok, output) = runner(inner = { _, _, _ ->
            calls.incrementAndGet()
            try { delay(10_000); true to "late" } finally { cancelled.complete(Unit) }
        }).execute("try { capability.call('host','first',{}); } catch(e) {} capability.call('host','second',{}); 'success';", 200)
        assertFalse(ok)
        assertTrue(output.contains("超时"))
        assertTrue(cancelled.isCompleted)
        assertEquals(1, calls.get())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `non cooperative inner return past deadline cannot report success`() = runBlocking {
        val (ok, output) = runner(inner = { _, _, _ -> Thread.sleep(150); true to "late" })
            .execute("capability.call('host','first',{}); 'success';", 50)
        assertFalse(ok)
        assertTrue(output.contains("超时"))
    }

    @Test
    fun `inner cancellation is propagated rather than converted to ordinary failure`() = runBlocking {
        val failure = runCatching {
            runner(inner = { _, _, _ -> throw CancellationException("stop") })
                .execute("try { capability.call('host','first',{}); } catch(e) {} 'success';", 10_000)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test
    fun `parent cancellation interrupts pure JS loop without waiting for deadline`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val running = async {
            runner(inner = { _, _, _ -> entered.complete(Unit); true to "ok" })
                .execute("capability.call('host','first',{}); while(true){}", 60_000)
        }
        withTimeout(5_000) { entered.await() }
        running.cancel()
        withTimeout(5_000) { running.cancelAndJoin() }
        assertTrue(running.isCancelled)
    }

    @Test
    fun `rhino evaluator stays loadable by its reflective string name`() {
        // Rhino 在 Context.interpreterClass 里用 Class.forName 按字符串名加载求值器，且
        // Kit.newInstanceOrNull 对 null Class 直接抛 NPE（catch 列表不含 NPE）。
        // 该断言把「类名常量与 rhino 版本一致」锁进单测，rhino 升级改名会立刻失败。
        val evaluator = Class.forName("org.mozilla.javascript.Interpreter", false, javaClass.classLoader)
        assertEquals(0, evaluator.getDeclaredConstructor().parameterCount)
    }

    @Test
    fun `consumer rules keep rhino reflective evaluator classes`() {
        // JVM 单测永远不经过 R8，所以这里守的是「release 构建的 keep 规则本身」：
        // 一旦被删，真机上 every script 会在编译阶段抛裸 NPE（v1.3.0 现场）。
        val rules = listOf("consumer-rules.pro", "harness/consumer-rules.pro")
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
        assertTrue("未找到 harness/consumer-rules.pro（cwd=${java.io.File(".").absolutePath}）", rules != null)
        val text = rules!!.readText()
        val kept = text.lineSequence().any { line ->
            line.trimStart().startsWith("-keep") && line.contains("org.mozilla.javascript.Interpreter")
        }
        assertTrue(
            "consumer-rules.pro 必须 keep org.mozilla.javascript.Interpreter：R8 会把这类仅按字符串名加载的类改名/剥离",
            kept,
        )
        // 反向守卫：keep Codegen 会把 Nashorn 的 Bootstrapper → jdk.dynalink.* 拖进引用图，
        // 直接炸掉 :app:minifyReleaseWithR8（release CI run 39412202648 实证）。
        val codegenKept = text.lineSequence().any { line ->
            line.trimStart().startsWith("-keep") && line.contains("optimizer.Codegen")
        }
        assertFalse(
            "不要 keep org.mozilla.javascript.optimizer.Codegen：Android 上 codegenClass 恒为 null，keep 它会引入 jdk.dynalink 缺类错误",
            codegenKept,
        )
    }

    // ---- sandbox JSON.parse：纯 Kotlin 解析器（SandboxJsonReader）合同 ----

    @Test
    fun `sandbox JSON parse reads objects nested arrays and members`() = runBlocking {
        val code = """
            var r = JSON.parse('{"n":1,"arr":[1,2],"o":{"k":"v"}}');
            r.n + ':' + r.arr.length + ':' + r.o.k
        """.trimIndent()

        val (ok, output) = runner().execute(code, 10_000)

        assertTrue("对象/嵌套数组解析失败：$output", ok)
        assertTrue("期望 1:2:v，实际：$output", output.contains("1:2:v"))
    }

    @Test
    fun `sandbox JSON parse handles primitives unicode escapes and exponents`() = runBlocking {
        val code = """
            var out = JSON.parse('123') + '|'
                + JSON.parse('-2.5e2') + '|'
                + JSON.parse('"a\\u0041\\n"') + '|'
                + JSON.parse('true') + '|'
                + JSON.parse('null');
            out
        """.trimIndent()

        val (ok, output) = runner().execute(code, 10_000)

        assertTrue("原始值解析失败：$output", ok)
        // -2.5e2 => -250；\u0041 => 'A'；\n => 真实换行；true/null => JS 原始值
        assertTrue("期望 123|-250|aA\\n|true|null，实际：$output", output.contains("123|-250|aA\n|true|null"))
    }

    @Test
    fun `sandbox JSON parse failure is catchable inside script`() = runBlocking {
        val code = """
            var flag = 'none';
            try { JSON.parse('{oops}'); } catch (e) { flag = 'caught'; }
            flag
        """.trimIndent()

        val (ok, output) = runner().execute(code, 10_000)

        // NativeJSON.parse 抛的是 Java 异常，会穿透 Rhino 直接炸掉整段脚本；
        // 纯 Kotlin 解析器抛 EvaluatorException，脚本内 try/catch 能接住。
        assertTrue("非法 JSON 不应让整脚本失败，ok=$ok output=$output", ok)
        assertTrue("脚本内 try/catch 必须接住解析异常，实际：$output", output.contains("caught"))
    }

    @Test
    fun `sandbox JSON stringify round trips a parsed structure`() = runBlocking {
        val code = """
            JSON.stringify(JSON.parse('{"a":[1,2,{"b":null}]}'))
        """.trimIndent()

        val (ok, output) = runner().execute(code, 10_000)

        assertTrue("round-trip 执行失败：$output", ok)
        assertTrue(
            """期望键序稳定地还原为 {"a":[1,2,{"b":null}]}，实际：$output""",
            output.contains("""{"a":[1,2,{"b":null}]}"""),
        )
    }
}
