package top.tianyan.app.ui.bot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.GroupToolStep
import top.tianyan.app.feature.bot.R

internal enum class OfficeRoleKind { GENERAL, FRONTEND, BACKEND, TEST, OPS, PRODUCT, DATA, DESIGN, WRITER }

internal fun officeRoleKind(role: String): OfficeRoleKind {
    val text = role.lowercase()
    return when {
        text.containsAny("前端", "front", "ui", "web") -> OfficeRoleKind.FRONTEND
        text.containsAny("测试", "qa", "test") -> OfficeRoleKind.TEST
        text.containsAny("运维", "ops", "sre", "deploy", "部署") -> OfficeRoleKind.OPS
        text.containsAny("产品", "product", "pm") -> OfficeRoleKind.PRODUCT
        text.containsAny("数据", "data", "分析", "analyst") -> OfficeRoleKind.DATA
        text.containsAny("设计", "design") -> OfficeRoleKind.DESIGN
        text.containsAny("后端", "back", "server", "api") -> OfficeRoleKind.BACKEND
        text.containsAny("文档", "writer", "doc", "写") -> OfficeRoleKind.WRITER
        else -> OfficeRoleKind.GENERAL
    }
}

private fun String.containsAny(vararg words: String): Boolean = words.any { contains(it) }

/** Latest meaningful sentence from the reasoning stream, kept short enough for a bubble. */
internal fun reasoningSnippet(reasoning: String, max: Int = 46): String? {
    val last = reasoning.lineSequence()
        .flatMap { line -> line.split(SENTENCE_SPLIT).asSequence() }
        .map { segment ->
            segment.trim()
                .trim('*', '`', '#', '>', '-', ' ')
                .replace(LEADING_NUMBER, "")
                .trim()
        }
        .filter { it.length >= 4 && it.any { char -> char.isLetterOrDigit() } }
        .lastOrNull()
        ?: return null
    val clean = last.replace(WHITESPACE, " ")
    return if (clean.length <= max) clean else clean.take(max).trimEnd() + "…"
}

/** The freshest line of streamed answer text, with the newest tail when it is long. */
internal fun outputSnippet(content: String, max: Int = 42): String? {
    val line = content.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() } ?: return null
    val clean = line.trim('*', '`', '#', '>', ' ').replace(WHITESPACE, " ").trim()
    if (clean.isBlank()) return null
    return if (clean.length <= max) clean else "…" + clean.takeLast(max).trimStart()
}

@Composable
internal fun officeStateLabel(state: BotAvatarState): String = when (state) {
    BotAvatarState.THINKING -> stringResource(R.string.fbot_desk_status_thinking)
    BotAvatarState.EXECUTING -> stringResource(R.string.fbot_desk_status_executing)
    BotAvatarState.ACTIVE -> stringResource(R.string.fbot_desk_status_active)
    BotAvatarState.BLOCKED -> stringResource(R.string.fbot_desk_status_blocked)
    BotAvatarState.SUCCESS -> stringResource(R.string.fbot_desk_status_success)
    BotAvatarState.FAILED -> stringResource(R.string.fbot_desk_status_failed)
    BotAvatarState.IDLE -> stringResource(R.string.fbot_desk_status_idle)
}

@Composable
internal fun officeIdleLine(bot: Bot, tick: Long): String {
    val lines = stringArrayResource(
        when (officeRoleKind(bot.role)) {
            OfficeRoleKind.FRONTEND -> R.array.fbot_office_idle_frontend
            OfficeRoleKind.BACKEND -> R.array.fbot_office_idle_backend
            OfficeRoleKind.TEST -> R.array.fbot_office_idle_test
            OfficeRoleKind.OPS -> R.array.fbot_office_idle_ops
            OfficeRoleKind.PRODUCT -> R.array.fbot_office_idle_product
            OfficeRoleKind.DATA -> R.array.fbot_office_idle_data
            OfficeRoleKind.DESIGN -> R.array.fbot_office_idle_design
            OfficeRoleKind.WRITER -> R.array.fbot_office_idle_writer
            OfficeRoleKind.GENERAL -> R.array.fbot_office_idle_general
        },
    )
    if (lines.isEmpty()) return stringResource(R.string.fbot_pixel_office_idle_line)
    val index = Math.floorMod((tick % lines.size).toInt() + bot.id.hashCode(), lines.size)
    return lines[index]
}

@Composable
internal fun officeStepAction(step: GroupToolStep): String {
    val name = step.name.lowercase()
    val detail = step.detail?.take(42)
    return when {
        name.contains("write") || name.contains("create") || name.contains("overwrite") ->
            if (detail != null) stringResource(R.string.fbot_office_step_write, detail)
            else stringResource(R.string.fbot_office_step_write_file)
        name.contains("edit") || name.contains("patch") ->
            if (detail != null) stringResource(R.string.fbot_office_step_edit, detail)
            else stringResource(R.string.fbot_office_step_edit_file)
        name.contains("read") || name.contains("open") ->
            if (detail != null) stringResource(R.string.fbot_office_step_read, detail)
            else stringResource(R.string.fbot_office_step_read_file)
        name.contains("assign") || name.contains("subagent") || name.contains("bot-management") ->
            if (detail != null) stringResource(R.string.fbot_office_step_delegate_detail, detail)
            else stringResource(R.string.fbot_office_step_delegate)
        name.contains("test") || detail?.contains("test", ignoreCase = true) == true ->
            stringResource(R.string.fbot_office_step_test)
        name.contains("shell") || name.contains("bash") || name.contains("exec") ||
            name.contains("command") || name.contains("run") ->
            if (detail != null) stringResource(R.string.fbot_office_step_command_detail, detail)
            else stringResource(R.string.fbot_office_step_command)
        name.contains("grep") || name.contains("search") || name.contains("glob") || name.contains("find") ->
            if (detail != null) stringResource(R.string.fbot_office_step_search, detail)
            else stringResource(R.string.fbot_office_step_search_any)
        name.contains("fetch") || name.contains("browse") || name.contains("http") || name.contains("web") ->
            stringResource(R.string.fbot_office_step_web)
        else -> stringResource(R.string.fbot_member_step, detail ?: step.name)
    }
}

/** Very short action for the tiny monitor above a desk. */
@Composable
internal fun officeScreenAction(step: GroupToolStep): String {
    val name = step.name.lowercase()
    return when {
        name.contains("write") || name.contains("create") || name.contains("overwrite") ->
            stringResource(R.string.fbot_office_screen_write)
        name.contains("edit") || name.contains("patch") -> stringResource(R.string.fbot_office_screen_edit)
        name.contains("read") || name.contains("open") -> stringResource(R.string.fbot_office_screen_read)
        name.contains("assign") || name.contains("subagent") || name.contains("bot-management") ->
            stringResource(R.string.fbot_office_screen_delegate)
        name.contains("test") -> stringResource(R.string.fbot_office_screen_test)
        name.contains("shell") || name.contains("bash") || name.contains("exec") ||
            name.contains("command") || name.contains("run") ->
            stringResource(R.string.fbot_office_screen_command)
        name.contains("grep") || name.contains("search") || name.contains("glob") || name.contains("find") ->
            stringResource(R.string.fbot_office_screen_search)
        name.contains("fetch") || name.contains("browse") || name.contains("http") || name.contains("web") ->
            stringResource(R.string.fbot_office_screen_web)
        else -> step.detail?.take(4) ?: step.name.take(4)
    }
}

private val SENTENCE_SPLIT = Regex("(?<=[。！？!?])|(?<=[.!?])\\s+")
private val LEADING_NUMBER = Regex("^\\d+[.)、]\\s*")
private val WHITESPACE = Regex("\\s+")
