package top.tianyan.app.core.security

import top.tianyan.app.core.common.logging.SensitiveDataRedactor

class SecretRedactor() : SensitiveDataRedactor {
    override fun redact(value: String): String {
        // Learn only within this entry so echoes of a labelled secret are also removed.
        // Do not retain credentials in a process-wide cache.
        val secrets = (SECRET_FIELDS.findAll(value).map { it.groupValues[3] } +
            BARE_SECRET_FIELDS.findAll(value).map { it.groupValues[2] })
            .filter { it.length >= 5 }.distinct().sortedByDescending { it.length }.toList()
        var result = value.replace(SECRET_FIELDS) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]${it.groupValues[2]}" }
        result = result.replace(BARE_SECRET_FIELDS) { "${it.groupValues[1]}[REDACTED]" }
        secrets.forEach { result = result.replace(it, "[REDACTED]") }
        return result
            .replace(Regex("(?i)(OPENAI_API_KEY|API_KEY|TOKEN|AUTHORIZATION)\\s*[=:]\\s*[^\\s,;]+"), "$1=[REDACTED]")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
            .replace(Regex("\\b(?:sk-(?:ant-|proj-)?|AIza|xox[bap]-)[A-Za-z0-9._-]{12,}"), "[REDACTED]")
            // Structured tool arguments, including JSON and JavaScript snippets embedded in JSON.
            .replace(JSON_SECRET_VALUE, "$1$2[REDACTED]$4")
            .replace(SCRIPT_SECRET_ASSIGNMENT, "$1$2[REDACTED]$4")
            // Agent/browser tools frequently place account identifiers directly in expressions.
            .replace(MAINLAND_PHONE, "[PHONE_REDACTED]")
    }

    fun redact(value: String, secretValues: Collection<String>, privacyMode: Boolean = true): String {
        if (!privacyMode) return redact(value)
        var result = redact(value)
        secretValues.asSequence()
            .filter { it.length >= 5 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { secret -> result = result.replace(secret, mask(secret)) }
        return result
    }

    private fun mask(secret: String): String = if (secret.length < 8) "*".repeat(secret.length)
    else secret.take(2) + "*".repeat(secret.length - 4) + secret.takeLast(2)

    private companion object {
        private const val SECRET_KEYS =
            "password|passwd|pwd|passcode|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|cookie|session[_-]?token"
        // Plain and JSON-escaped assignments, prefixed config fields and CLI options.
        private val SECRET_FIELDS = Regex(
            """(?i)((?<![\w-])(?:--)?(?:[a-z0-9]+[_-])*(?:$SECRET_KEYS|pass|token)(?:\\*["'])?(?:\.value)?\s*(?:[=:]\s*|\s+))(\\*["'])(.*?)(\2)""",
        )
        private val BARE_SECRET_FIELDS = Regex(
            """(?i)((?<![\w-])(?:--)?(?:[a-z0-9]+[_-])*(?:$SECRET_KEYS|pass|token)\s*[=:]\s*)([^\s,;"'\\}\[\]]+)""",
        )
        private val JSON_SECRET_VALUE = Regex(
            "(?i)([\\\"'](?:$SECRET_KEYS)[\\\"']\\s*:\\s*)([\\\"'])(.*?)(\\2)",
        )
        private val SCRIPT_SECRET_ASSIGNMENT = Regex(
            "(?i)(\\b(?:$SECRET_KEYS)(?:\\.value)?\\s*=\\s*)([\\\"'])(.*?)(\\2)",
        )
        private val MAINLAND_PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    }
}
