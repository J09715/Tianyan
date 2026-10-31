package top.tianyan.app.harness.model

/**
 * 按模型 id 的 token 序列匹配元数据（移植自 rikkahub 的 ModelDsl）。
 *
 * `gpt-5-mini` 切成 [gpt, -, 5, -, mini]，`tokens("gpt", "5")` 按序命中即匹配；
 * 匹配支持打分，同分时多个定义合并，具体规则见 [ModelMetadataRegistry]。
 */
interface ModelSelector {
    fun match(modelId: String): Boolean
}

class ModelDefinition internal constructor(
    private val matcher: TokenMatcher,
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
    val contextLength: Int? = null,
) : ModelSelector {
    override fun match(modelId: String): Boolean {
        val tokens = tokenize(modelId)
        return matcher.score(modelId, tokens) != null
    }

    internal fun matchScore(modelId: String, tokens: List<String>): Int? =
        matcher.score(modelId, tokens)
}

fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition =
    ModelDefinitionBuilder().apply(block).build()

fun tokenRegex(pattern: String): TokenSpec = TokenRegex(pattern.toRegex(RegexOption.IGNORE_CASE))

class ModelDefinitionBuilder {
    private val matchers = mutableListOf<TokenMatcher>()
    private val inputModalities = mutableSetOf(Modality.TEXT)
    private val outputModalities = mutableSetOf(Modality.TEXT)
    private val abilities = mutableSetOf<ModelAbility>()
    private var contextLength: Int? = null

    fun tokens(vararg specs: String) {
        matchers += TokenSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun tokens(vararg specs: TokenSpec) {
        matchers += TokenSequenceMatcher(specs.toList())
    }

    fun notTokens(vararg specs: String) {
        matchers += NotTokenSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun notTokens(vararg specs: TokenSpec) {
        matchers += NotTokenSequenceMatcher(specs.toList())
    }

    fun exact(id: String) {
        matchers += ExactIdMatcher(id)
    }

    fun input(vararg modalities: Modality) {
        inputModalities.clear()
        inputModalities.addAll(modalities)
    }

    fun output(vararg modalities: Modality) {
        outputModalities.clear()
        outputModalities.addAll(modalities)
    }

    fun ability(vararg abilities: ModelAbility) {
        this.abilities.addAll(abilities)
    }

    fun contextLength(tokens: Int) {
        contextLength = tokens
    }

    fun build(): ModelDefinition {
        val matcher = when {
            matchers.isEmpty() -> MatchNone
            matchers.size == 1 -> matchers.first()
            else -> AndMatcher(matchers.toList())
        }
        return ModelDefinition(matcher, inputModalities.toSet(), outputModalities.toSet(), abilities.toSet(), contextLength)
    }
}

sealed interface TokenSpec {
    fun matches(token: String): Boolean
}

private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
    override fun matches(token: String): Boolean = token in options
}

private data class TokenRegex(val regex: Regex) : TokenSpec {
    override fun matches(token: String): Boolean = regex.matches(token)
}

internal interface TokenMatcher {
    fun score(modelId: String, tokens: List<String>): Int?
}

private object MatchNone : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? = null
}

private class AndMatcher(
    private val matchers: List<TokenMatcher>,
) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        var total = 0
        for (matcher in matchers) {
            val score = matcher.score(modelId, tokens) ?: return null
            total += score
        }
        return total
    }
}

private class ExactIdMatcher(
    private val id: String,
) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        return if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
    }
}

private class TokenSequenceMatcher(
    private val specs: List<TokenSpec>,
) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        if (specs.isEmpty()) return null
        var specIndex = 0
        for ((index, token) in tokens.withIndex()) {
            if (isMagnitude(tokens, index)) continue
            if (specs[specIndex].matches(token)) {
                specIndex += 1
                if (specIndex == specs.size) return specs.size
            }
        }
        return null
    }
}

private class NotTokenSequenceMatcher(
    specs: List<TokenSpec>,
) : TokenMatcher {
    private val matcher = TokenSequenceMatcher(specs)

    override fun score(modelId: String, tokens: List<String>): Int? {
        return if (matcher.score(modelId, tokens) == null) 0 else null
    }
}

private fun parseTokenSpec(spec: String): TokenSpec {
    val options = spec.split('|')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    return TokenAlternatives(options)
}

private const val EXACT_ID_BONUS = 1000

// 紧跟这些单位的数字是参数量或上下文长度（如 8b、32k），不是版本号
private val MAGNITUDE_SUFFIXES = setOf("b", "k")

private fun isMagnitude(tokens: List<String>, index: Int): Boolean =
    tokens[index].first().isDigit() && tokens.getOrNull(index + 1) in MAGNITUDE_SUFFIXES

internal fun tokenize(modelId: String): List<String> {
    val tokens = mutableListOf<String>()
    val input = modelId.lowercase()
    var index = 0
    while (index < input.length) {
        val ch = input[index]
        when {
            ch.isLetter() -> {
                val start = index
                while (index < input.length && input[index].isLetter()) index += 1
                tokens.add(input.substring(start, index))
            }

            ch.isDigit() -> {
                val start = index
                while (index < input.length && input[index].isDigit()) index += 1
                tokens.add(input.substring(start, index))
            }

            else -> {
                tokens.add(ch.toString())
                index += 1
            }
        }
    }
    return tokens
}
