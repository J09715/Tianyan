package top.tianyan.app.ui.settings.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.database.UsageLedgerRow
import top.tianyan.app.core.database.UsageLedgerSessionRow
import java.time.LocalDate

/**
 * [assembleLedger] 的行为约束。
 *
 * 这些用例锁的是**容易被顺手改坏、且错了不会报错只会算错数**的地方：
 * 作用域过滤后的总量、取值列表的口径、趋势的日切边界、以及空 modelId 的归并 key。
 *
 * 注意：`feature:settings` 目前不在 PR 的 `harness-contracts` 覆盖范围内，
 * 因此这组用例不会被该工作流执行——需要单独跑 `:feature:settings:testDebugUnitTest`。
 */
class StatsLedgerAssemblerTest {

    private val trendStart: LocalDate = LocalDate.of(2026, 10, 1)
    private val trendEnd: LocalDate = LocalDate.of(2026, 10, 31)

    private fun row(
        day: String,
        provider: String = "deepseek",
        modelId: String = "deepseek-chat",
        input: Long = 100L,
        output: Long = 40L,
        reasoning: Long = 10L,
        cacheRead: Long = 0L,
        cacheWrite: Long = 0L,
        calls: Int = 1,
    ): UsageLedgerRow = UsageLedgerRow(
        localDay = day,
        provider = provider,
        modelId = modelId,
        inputTokens = input,
        outputTokens = output,
        reasoningTokens = reasoning,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        calls = calls,
    )

    private fun assemble(
        ledgerRows: List<UsageLedgerRow>,
        baseDayRows: List<UsageLedgerRow> = ledgerRows,
        ledgerSessionRows: List<UsageLedgerSessionRow> = emptyList(),
        baseSessionRows: List<UsageLedgerSessionRow> = emptyList(),
    ) = assembleLedger(
        ledgerRows = ledgerRows,
        ledgerSessionRows = ledgerSessionRows,
        recentRows = emptyList(),
        baseDayRows = baseDayRows,
        baseSessionRows = baseSessionRows,
        trendStart = trendStart,
        trendEnd = trendEnd,
        modelLabelOf = { it.ifBlank { "未标注模型" } },
        providerLabelOf = { provider, _ -> provider.ifBlank { "内置" } },
        sessionLabelOf = { "会话 " + it },
    )

    @Test
    fun `totals come only from the already scoped rows`() {
        val scoped = listOf(row("2026-10-05", input = 100L, output = 40L, reasoning = 10L))
        val unscoped = scoped + row("2026-10-06", modelId = "gpt-5.1", input = 900L, output = 900L)

        val ledger = assemble(ledgerRows = scoped, baseDayRows = unscoped)

        // 作用域过滤由仓库负责，组装只对传入行求和——不能顺手把未限定行也算进来
        assertEquals(1, ledger.calls)
        assertEquals(100L, ledger.inputTokens)
        assertEquals(150L, ledger.totalTokens)
    }

    @Test
    fun `scope options always come from the unscoped rows`() {
        val unscoped = listOf(
            row("2026-10-05", modelId = "deepseek-chat"),
            row("2026-10-06", modelId = "gpt-5.1", provider = "openai"),
        )

        // 当前作用域只落在 gpt-5.1 上
        val ledger = assemble(ledgerRows = listOf(unscoped[1]), baseDayRows = unscoped)

        // 取值列表若跟着作用域走，选中一项后其余胶囊就会消失、再也切不回去
        assertEquals(
            listOf("deepseek-chat", "gpt-5.1"),
            ledger.scopeOptions.models.map { it.id }.sorted(),
        )
        assertEquals(listOf("deepseek", "openai"), ledger.scopeOptions.providers.map { it.id }.sorted())
    }

    @Test
    fun `trend drops days outside the trend window but totals keep them`() {
        val rows = listOf(
            row("2026-09-30"),
            row("2026-10-01"),
            row("2026-10-31"),
            row("2026-11-01"),
        )

        val ledger = assemble(ledgerRows = rows)

        assertEquals(
            listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
            ledger.trend.map { it.date },
        )
        // 窗口只约束趋势曲线，总量仍是四行全算
        assertEquals(4, ledger.calls)
    }

    @Test
    fun `blank model id merges under the provider label`() {
        val rows = listOf(
            row("2026-10-05", provider = "openai", modelId = "", input = 10L),
            row("2026-10-06", provider = "openai", modelId = "", input = 20L),
        )

        val ledger = assemble(ledgerRows = rows)

        // 两行必须归并成同一个 key，否则模型维度会冒出重复行
        assertEquals(1, ledger.byModel.size)
        assertEquals("openai", ledger.byModel.first().id)
        assertEquals(30L, ledger.byModel.first().inputTokens)
        assertEquals(2, ledger.byModel.first().calls)
    }

    @Test
    fun `cache read is excluded from the billed total`() {
        val rows = listOf(row("2026-10-05", input = 100L, output = 40L, reasoning = 10L, cacheRead = 7_000L))

        val ledger = assemble(ledgerRows = rows)

        // 命中读取是输入前缀的复用部分，计入会与输入双重计算
        assertEquals(150L, ledger.totalTokens)
        assertEquals(7_000L, ledger.cacheReadTokens)
    }
}
