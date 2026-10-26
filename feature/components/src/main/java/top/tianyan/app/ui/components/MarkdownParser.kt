package top.tianyan.app.ui.components

import android.util.LruCache

internal sealed interface MdBlock

internal data class MdParagraph(val text: String) : MdBlock
internal data class MdHeading(val level: Int, val text: String) : MdBlock
internal data class MdCodeBlock(val language: String, val code: String) : MdBlock
internal data class MdList(val ordered: Boolean, val items: List<String>) : MdBlock
internal data class MdQuote(val lines: List<String>) : MdBlock
internal data class MdTable(val headers: List<String>, val rows: List<List<String>>) : MdBlock
internal data class MdRemoteMedia(val url: String, val description: String) : MdBlock
internal object MdHr : MdBlock

private val headingRegex = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val orderedListRegex = Regex("^\\s*\\d+\\.\\s+(.*)$")
private val unorderedListRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val hrRegex = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val markdownImageRegex = Regex(
    pattern = """^\s*!\[([^]\n]*)]\(((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^)\s]+)\)\s*$""",
    option = RegexOption.IGNORE_CASE,
)
/** 行内图片（不要求独占一行），用于从段落文本中拆出图片块。 */
private val inlineImageRegex = Regex("""!\[([^]\n]*)]\(((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^)\s]+)\)""", RegexOption.IGNORE_CASE)
/** HTML <img> 标签，兼容模型直接输出 img 标签的情况。 */
private val htmlImgRegex = Regex("""<img[^>]+src\s*=\s*["']((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^"'\s]+)["'][^>]*>""", RegexOption.IGNORE_CASE)
private val standaloneWebUrlRegex = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
internal val inlineMarkdownLinkRegex = Regex("\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)", RegexOption.IGNORE_CASE)
internal val inlineWebUrlRegex = Regex("https?://[^\\s<>\\[\\]{}\"']+", RegexOption.IGNORE_CASE)
/** 视为图片的 URL 后缀（原始 URL 嵌在段落中时，匹配这些后缀则渲染为图片）。 */
private val imageFileExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "avif", "heic", "heif")

private val markdownBlockCache by lazy { LruCache<String, List<MdBlock>>(300) }
/** Generated image / oversized markdown is expensive to parse; retain a few by message id + content fingerprint. */
private val largeMarkdownBlockCache by lazy { LruCache<String, CachedMarkdownBlocks>(4) }

private data class CachedMarkdownBlocks(
    val fingerprint: Int,
    val blocks: List<MdBlock>,
)

private fun markdownContentFingerprint(markdown: String): Int = 31 * markdown.length + markdown.hashCode()

/**
 * 解析 markdown。大文本 LRU 以 [contentCacheKey]（通常是 message.id）为槽位，
 * 命中时必须核对内容指纹，避免同一 id 换了正文仍复用旧块。
 *
 * [largeCacheMinChars] 仅测试可覆盖；生产走 [MARKDOWN_CACHE_MAX_CHARS]。
 * 流式短消息不会写入大文本缓存，remember(key, markdown) 仍按正文更新。
 */
internal fun resolveMarkdownBlocks(
    markdown: String,
    contentCacheKey: String?,
    largeCacheMinChars: Int = MARKDOWN_CACHE_MAX_CHARS,
): List<MdBlock> {
    if (contentCacheKey != null && markdown.length > largeCacheMinChars) {
        val cached = largeMarkdownBlockCache.get(contentCacheKey)
        if (cached != null && cached.fingerprint == markdownContentFingerprint(markdown)) {
            return cached.blocks
        }
    }
    val parsed = parseMarkdownBlocks(markdown)
    if (contentCacheKey != null && markdown.length > largeCacheMinChars) {
        largeMarkdownBlockCache.put(
            contentCacheKey,
            CachedMarkdownBlocks(markdownContentFingerprint(markdown), parsed),
        )
    }
    return parsed
}

internal fun parseMarkdownBlocks(md: String, cache: Boolean = true): List<MdBlock> {
    val cacheable = cache && md.length <= MARKDOWN_CACHE_MAX_CHARS
    if (cacheable) markdownBlockCache.get(md)?.let { return it }
    val raw = parseRawBlocks(md)
    // 段落后处理：把行内的 ![alt](url)、<img src="url">、原始图片 URL 拆成独立图片块，
    // 否则模型带前后文字输出图片时，解析器只当纯文本渲染，Coil 永远不会被调用。
    val parsed = raw.flatMap { block ->
        if (block is MdParagraph) extractInlineMedia(block.text)
        else listOf(block)
    }
    if (cacheable) markdownBlockCache.put(md, parsed)
    return parsed
}

private fun parseRawBlocks(md: String): List<MdBlock> {
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.add(lines[i]); i++
                }
                i++ // skip closing fence
                blocks.add(MdCodeBlock(lang, code.joinToString("\n").trim('\n')))
            }
            markdownImageRegex.matches(line) -> {
                val match = markdownImageRegex.matchEntire(line)!!
                blocks.add(
                    MdRemoteMedia(
                        url = match.groupValues[2],
                        description = match.groupValues[1],
                    ),
                )
                i++
            }
            standaloneWebUrlRegex.matches(line.trim()) -> {
                val url = trimUrlPunctuation(line.trim())
                blocks.add(MdRemoteMedia(url = url, description = ""))
                i++
            }
            headingRegex.matches(line.trim()) -> {
                val m = headingRegex.find(line.trim())!!
                blocks.add(MdHeading(m.groupValues[1].length, m.groupValues[2]))
                i++
            }
            isTableStart(lines, i) -> {
                val headers = splitRow(lines[i])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (lines[i].isBlank() || !t.startsWith("|")) break
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdTable(headers, rows))
            }
            hrRegex.matches(line) -> { blocks.add(MdHr); i++ }
            trimmed.startsWith(">") -> {
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdQuote(quoted))
            }
            orderedListRegex.matches(line) || unorderedListRegex.matches(line) -> {
                val ordered = orderedListRegex.matches(lines[i])
                val items = mutableListOf<String>()
                while (i < lines.size && (orderedListRegex.matches(lines[i]) || unorderedListRegex.matches(lines[i]))) {
                    val l = lines[i]
                    val m = orderedListRegex.find(l) ?: unorderedListRegex.find(l)
                    items.add(m!!.groupValues[1])
                    i++
                }
                blocks.add(MdList(ordered, items))
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (l.isBlank() ||
                        headingRegex.matches(l.trim()) ||
                        hrRegex.matches(l) ||
                        isTableStart(lines, i) ||
                        t.startsWith("```") ||
                        t.startsWith(">") ||
                        markdownImageRegex.matches(l) ||
                        standaloneWebUrlRegex.matches(l.trim()) ||
                        orderedListRegex.matches(l) ||
                        unorderedListRegex.matches(l)
                    ) break
                    para.add(l); i++
                }
                blocks.add(MdParagraph(para.joinToString(" ").trim()))
            }
        }
    }
    return blocks
}

/**
 * 从段落文本中拆出行内媒体（Markdown 图片、HTML img、原始图片 URL）。
 * 返回文本段落与图片块的有序列表；无媒体时返回单段落。
 */
private fun extractInlineMedia(text: String): List<MdBlock> {
    if (text.isBlank()) return listOf(MdParagraph(text))
    val result = mutableListOf<MdBlock>()
    val textBuf = StringBuilder()
    var remaining = text

    while (remaining.isNotEmpty()) {
        // 优先级：Markdown 图片 > HTML img > 原始图片 URL
        val mdImg = inlineImageRegex.find(remaining)
        val htmlImg = htmlImgRegex.find(remaining)
        val rawUrl = inlineWebUrlRegex.find(remaining)

        val earliest = listOfNotNull(mdImg, htmlImg, rawUrl)
            .minByOrNull { it.range.first }

        if (earliest == null) {
            textBuf.append(remaining)
            break
        }

        // 匹配到的媒体之前的文本
        if (earliest.range.first > 0) {
            textBuf.append(remaining, 0, earliest.range.first)
        }

        val isActualImage = when (earliest) {
            mdImg -> true
            htmlImg -> true
            rawUrl -> {
                val url = trimUrlPunctuation(earliest.value)
                imageFileExtensions.any { ext -> url.lowercase().endsWith(".$ext") }
            }
            else -> false
        }

        if (isActualImage) {
            // 先 flush 累积的文本
            if (textBuf.isNotBlank()) {
                result.add(MdParagraph(textBuf.toString().trim()))
                textBuf.clear()
            }
            val url = when (earliest) {
                mdImg -> earliest.groupValues[2]
                htmlImg -> earliest.groupValues[1]
                else -> trimUrlPunctuation(earliest.value)
            }
            val desc = if (earliest === mdImg) earliest.groupValues[1] else ""
            result.add(MdRemoteMedia(url = url, description = desc))
        } else {
            // 普通 URL，保留为文本（行内渲染会把它变成可点击链接）
            textBuf.append(remaining, earliest.range.first, earliest.range.last + 1)
        }

        remaining = remaining.substring(earliest.range.last + 1)
    }

    if (textBuf.isNotBlank()) {
        result.add(MdParagraph(textBuf.toString().trim()))
    }

    return if (result.isEmpty()) listOf(MdParagraph(text)) else result
}

// ---------- 行内渲染 ----------


private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    val cells = t.trim('|').split("|")
    if (cells.size < 2) return false
    return cells.all { c ->
        val s = c.trim()
        s.isEmpty() || s.all { ch -> ch == '-' || ch == ':' || ch.isWhitespace() }
    }
}

private fun isTableStart(lines: List<String>, i: Int): Boolean =
    i + 1 < lines.size && lines[i].trimStart().startsWith("|") && isTableSeparator(lines[i + 1])


internal fun trimUrlPunctuation(value: String): String {
    var result = value.trimEnd('.', ',', ';', ':', '!', '?')
    while (result.endsWith(')') && result.count { it == ')' } > result.count { it == '(' }) {
        result = result.dropLast(1)
    }
    return result
}


internal const val MARKDOWN_CACHE_MAX_CHARS = 128_000

internal fun clearParsedMarkdownCaches() {
    markdownBlockCache.evictAll()
    largeMarkdownBlockCache.evictAll()
}
