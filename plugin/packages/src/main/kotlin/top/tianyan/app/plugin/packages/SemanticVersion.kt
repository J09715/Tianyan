package top.tianyan.app.plugin.packages

/** SemVer precedence without integer overflow. Build metadata does not affect precedence. */
class SemanticVersion private constructor(
    private val numbers: List<String>,
    private val preRelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            compareNumber(left, right).takeIf { it != 0 }?.let { return it }
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return when { preRelease.isEmpty() && other.preRelease.isEmpty() -> 0; preRelease.isEmpty() -> 1; else -> -1 }
        }
        preRelease.zip(other.preRelease).forEach { (left, right) ->
            val numericLeft = left.all(Char::isDigit)
            val numericRight = right.all(Char::isDigit)
            val comparison = when {
                numericLeft && numericRight -> compareNumber(left, right)
                numericLeft -> -1
                numericRight -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val syntax = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        fun parse(value: String): SemanticVersion? {
            if (value.length > 128) return null
            val match = syntax.matchEntire(value) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return SemanticVersion(match.groupValues.subList(1, 4), pre)
        }

        private fun compareNumber(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
