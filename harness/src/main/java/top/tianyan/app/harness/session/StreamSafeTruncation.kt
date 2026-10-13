package top.tianyan.app.harness.session

/**
 * 流式文本的安全截断，供 live 期间做增量 Markdown 排版。
 *
 * ## 为什么不能在任意字符处截断
 *
 * 模型是逐块吐出的。若在 ``` 围栏中间或列表/标题行中间断开，解析器会把未闭合的
 * 围栏当作代码块一直吃到底 —— 整屏变成一个巨大代码块，比"输出完才排版"更难看。
 *
 * 因此只在以下位置截断（优先级从高到低）：
 * 1. 已闭合 ``` 围栏之后（窗口内最后一个闭合围栏的结束位置）；
 * 2. 空行分隔的段落边界（标题、列表、引用的天然分界）；
 * 3. 最后一个换行处（至少不把一行切成两半）；
 * 4. 兜底：尾部定长窗口（宁可难看，也不能无界）。
 *
 * 放在 harness 而不是 UI 层：这是纯字符串处理，与 Android/Compose 无关，
 * 放这里才能被单元测试直接覆盖。
 */
fun truncateAtSafeMarkdownBoundary(text: String, window: Int): String {
    if (window <= 0) return ""
    if (text.length <= window) return text
    val start = text.length - window

    // 统一策略：**从窗口起点向后扫描**找第一个安全边界。
    // 这样边界天然落在窗口内（不需要「往前回退再判断」那套容易算错的逻辑），
    // 且天然保证结果非空 —— 因为边界之后一定还有内容（否则就不是边界）。
    //
    // 优先级：闭合围栏结束 > 空行段落 > 单个换行。取其中**最早出现**的那个，
    // 这样保留的内容最多。

    // 1) 闭合围栏的结束位置。
    //    从文本开头正向配对围栏，记录每一对的结束位置；取「结束位置落在窗口内」
    //    的最后一个。这样即使窗口起点落在某个围栏标记中间（窗口内容以半截围栏
    //    开头），也能正确识别出「上一个围栏已经闭合」，从而安全地切在它之后。
    //
    //    反例（我踩过的坑）：若只从 start 向后扫描，start 后的第一个 ``` 其实是
    //    **闭合**围栏，会被误当成开围栏且找不到配对 → 判为未闭合 → 回退到换行
    //    边界，而换行边界恰好切在围栏上，结果残留 ``` 标记。
    var fenceEnd = -1
    var scan = 0
    while (scan < text.length) {
        val open = text.indexOf("```", scan)
        if (open < 0) break
        val close = text.indexOf("```", open + 3)
        if (close < 0) break // 未闭合 → 其后一律不安全，停止
        fenceEnd = close + 3
        scan = close + 3
    }
    // 2) 空行段落边界 / 3) 单个换行边界（仅当围栏边界不可用时才考虑）
    //
    // 注意优先级：围栏边界必须**优先**于换行边界。若取「最早出现的边界」，
    // 围栏标记自身的换行会被选中，结果切在 ``` 上 —— 残留围栏标记。
    if (fenceEnd > start && fenceEnd < text.length) return text.substring(fenceEnd)

    val blank = text.indexOf("\n\n", start)
    if (blank >= start && blank + 2 < text.length) {
        var p = blank + 2
        while (p < text.length && text[p] == '\n') p++
        if (p < text.length) return text.substring(p)
    }

    val newline = text.indexOf('\n', start)
    if (newline >= start && newline + 1 < text.length) {
        var p = newline + 1
        while (p < text.length && text[p] == '\n') p++
        if (p < text.length) return text.substring(p)
    }

    // 4) 兜底：定长尾部窗口
    return text.takeLast(window)
}

/** 归档类后缀：这类文件模型拿到后应先解压，而不是直接当文本读。 */
private val ARCHIVE_EXTENSIONS = listOf(".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".rar", ".7z")

/** 是否是需要提示"先解压"的归档文件。 */
fun isArchiveFileName(name: String): Boolean =
    ARCHIVE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
