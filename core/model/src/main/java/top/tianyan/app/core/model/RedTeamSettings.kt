package top.tianyan.app.core.model

/**
 * 红队插件级设置，移植自上游 `settings.js`。目前只有一项：**并发执行智能体上限**。
 *
 * 上游特意解释了为什么不用环境变量：`REDTEAM_MAX_AGENTS` 只能启动前设，改完得重启；
 * 而「同时派几个执行智能体」是使用中最常调的一项，界面上应该能直接改。
 * 生效顺序：设置项 → 环境变量 `REDTEAM_MAX_AGENTS` → 默认 3。
 *
 * 文件读写的落地由调用方注入，这一层只保留纯逻辑，便于单测。
 */
object RedTeamSettings {

    /** 默认并发上限（用户口径：整个项目默认 3 个执行智能体）。 */
    const val DEFAULT_MAX_AGENTS = 3

    /** 硬上限：再多也不会更快 —— 同一个 API Key 的并发/速率限制会先到，反而扰动测试。 */
    const val MAX_AGENTS_LIMIT = 10

    const val ENV_MAX_AGENTS = "REDTEAM_MAX_AGENTS"
    const val SETTINGS_FILE_NAME = "settings.json"

    /** 设置项来源，界面上要如实说明，避免「我改了没生效」的错觉。 */
    enum class MaxAgentsSource(val id: String) {
        SETTINGS("settings"),
        ENV("env"),
        DEFAULT("default"),
    }

    data class MaxAgents(val value: Int, val source: MaxAgentsSource)

    /**
     * 把任意输入收敛到 `[1, MAX_AGENTS_LIMIT]` 的整数。
     *
     * 两个容易搞反的边界，照上游语义保留：
     *   · `null` 当 0 处理（JS `Number(null) === 0`），收敛结果是 **1** 而不是默认值 3；
     *   · 完全无法解析的输入（`undefined`/`abc`/`NaN`）才回落到默认值 3。
     */
    fun clampMaxAgents(value: Any?): Int {
        val n: Double = when (value) {
            is Number -> value.toDouble()
            null -> 0.0
            else -> value.toString().trim().let { s -> if (s.isEmpty()) 0.0 else s.toDoubleOrNull() ?: Double.NaN }
        }
        if (!n.isFinite()) return DEFAULT_MAX_AGENTS
        return Math.floor(n).toInt().coerceIn(1, MAX_AGENTS_LIMIT)
    }

    /**
     * 当前生效的并发上限。
     *
     * 注意 `0` 与负数被当作「未设置」而不是「收敛到 1」——
     * 上游用的是 `> 0` 判断，照搬以免把「有人填了 0」误解释成「只允许 1 个」。
     */
    fun maxAgentsOf(settingsValue: Any?, envValue: String?): MaxAgents {
        val fromFile = settingsValue?.let { it as? Number }?.toDouble() ?: settingsValue?.toString()?.trim()?.toDoubleOrNull()
        if (fromFile != null && fromFile.isFinite() && fromFile > 0) {
            return MaxAgents(clampMaxAgents(fromFile), MaxAgentsSource.SETTINGS)
        }
        val fromEnv = envValue?.trim()?.toDoubleOrNull()
        if (fromEnv != null && fromEnv.isFinite() && fromEnv > 0) {
            return MaxAgents(clampMaxAgents(fromEnv), MaxAgentsSource.ENV)
        }
        return MaxAgents(DEFAULT_MAX_AGENTS, MaxAgentsSource.DEFAULT)
    }

    /** 上游 API 形状的便捷重载。 */
    fun maxAgentsValue(settingsValue: Any?, envValue: String?): Int = maxAgentsOf(settingsValue, envValue).value

    /** 解析 settings.json 里的 maxAgents 字段；读不到/坏了都当未设置，绝不抛。 */
    fun maxAgentsFromJson(json: String?): Double? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            obj["maxAgents"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content?.trim()?.toDoubleOrNull()
        }.getOrNull()
    }

    /** 生成写回的 settings.json 内容（保留其它字段）。 */
    fun withMaxAgents(existingJson: String?, applied: Int): String {
        val existing = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(existingJson ?: "{}")
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val merged = kotlinx.serialization.json.JsonObject(
            existing + ("maxAgents" to kotlinx.serialization.json.JsonPrimitive(applied)),
        )
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            merged,
        ) + "\n"
    }
}