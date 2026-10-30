package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

interface BackupPreferenceStore {
    suspend fun snapshot(): JsonObject
    fun validate(values: JsonObject)
    suspend fun replace(expected: JsonObject, values: JsonObject, operationId: String)
    suspend fun rollback(operationId: String, applied: JsonObject, previous: JsonObject)
}

/** Portable preference facade. Never reads credentials, pairing, mounts, services or machine paths. */
class BackupPreferences(private val context: Context) : BackupPreferenceStore {
    private val store get() = context.settingsDataStore
    override suspend fun snapshot(): JsonObject = portable(store.data.first())

    override fun validate(values: JsonObject) {
        require(values.keys.all { it in strings || it in booleans || it in integers || it == "app_font_scale" }) { "备份包含不支持的偏好设置" }
        values.forEach { (name, value) ->
            require(value is JsonPrimitive) { "偏好设置类型无效" }
            if (value != JsonNull) require(when {
                name in strings -> value.isString && value.content.length <= 262_144
                name in booleans -> !value.isString && value.booleanOrNull != null
                name in integers -> !value.isString && value.intOrNull in integers.getValue(name)
                else -> !value.isString && value.floatOrNull?.let { it.isFinite() && it in 0.8f..1.3f } == true
            }) { "偏好设置类型或范围无效" }
        }
    }

    override suspend fun replace(expected: JsonObject, values: JsonObject, operationId: String) {
        validate(values)
        store.edit { prefs ->
            check(portable(prefs) == expected) { "偏好设置已变化，请重新预览" }
            names.forEach { write(prefs, it, values[it] ?: JsonNull) }
            prefs[operationKey] = operationId
        }
    }

    override suspend fun rollback(operationId: String, applied: JsonObject, previous: JsonObject) {
        validate(previous); validate(applied)
        store.edit { prefs ->
            if (prefs[operationKey] != operationId) return@edit
            val current = portable(prefs)
            names.filter { current[it] == (applied[it] ?: JsonNull) }.forEach { write(prefs, it, previous[it] ?: JsonNull) }
            prefs.remove(operationKey)
        }
    }

    private fun portable(prefs: Preferences): JsonObject = JsonObject(names.associateWith { name ->
        when {
            name in strings -> prefs[stringPreferencesKey(name)]?.let(::JsonPrimitive)
            name in booleans -> prefs[booleanPreferencesKey(name)]?.let(::JsonPrimitive)
            name in integers -> prefs[intPreferencesKey(name)]?.let(::JsonPrimitive)
            else -> prefs[floatPreferencesKey(name)]?.let(::JsonPrimitive)
        } ?: JsonNull
    })

    private fun write(prefs: MutablePreferences, name: String, value: JsonElement) {
        when {
            name in strings -> stringPreferencesKey(name).let { if (value == JsonNull) prefs.remove(it) else prefs[it] = value.jsonPrimitive.content }
            name in booleans -> booleanPreferencesKey(name).let { if (value == JsonNull) prefs.remove(it) else prefs[it] = value.jsonPrimitive.boolean }
            name in integers -> intPreferencesKey(name).let { if (value == JsonNull) prefs.remove(it) else prefs[it] = value.jsonPrimitive.int }
            else -> floatPreferencesKey(name).let { if (value == JsonNull) prefs.remove(it) else prefs[it] = value.jsonPrimitive.float }
        }
    }

    companion object {
        private val operationKey = stringPreferencesKey("backup_restore_operation_id")
        private val strings = setOf("theme_mode", "theme_style", "terminal_color_scheme", "agent_default_reasoning_depth",
            "custom_system_prompt", "agent_char_name", "agent_user_name")
        private val booleans = setOf("dynamic_color_enabled", "thinking_blocks_expanded", "thinking_auto_translate",
            "agent_local_logging_enabled", "custom_system_prompt_enabled", "environment_privacy_mode",
            "skill_evolution_suggestions", "terminal_haptics_enabled", "auto_check_updates",
            "agent_context_compaction_enabled", "agent_auto_workspace_cwd", "agent_command_output_compression_enabled")
        private val integers = mapOf("terminal_font_size" to 10..24, "agent_max_concurrent_turns" to 1..10,
            "agent_max_tool_rounds" to 10..300, "agent_round_limit_auto_continuations" to 0..10,
            "agent_base_command_timeout_seconds" to 60..3600, "agent_context_budget_tokens" to 4000..2_000_000,
            "agent_context_folding_ratio_percent" to 10..100, "agent_max_tools_per_round" to 1..50,
            "agent_max_consecutive_failures" to 1..50)
        private val names = strings + booleans + integers.keys + "app_font_scale"
    }
}
