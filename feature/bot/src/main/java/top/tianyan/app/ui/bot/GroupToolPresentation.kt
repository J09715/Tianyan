package top.tianyan.app.ui.bot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeIconName

private fun groupToolPresentation(name: String): Pair<RuntimeIconName, Int?> = when (name.lowercase(Locale.ROOT)) {
    "read", "history_read", "load_rule", "load_skill" -> RuntimeIconName.Document to R.string.fbot_process_read
    "write" -> RuntimeIconName.WriteDocument to R.string.fbot_process_write
    "edit" -> RuntimeIconName.Edit to R.string.fbot_process_edit
    "base", "shell", "bash", "exec" -> RuntimeIconName.Terminal to R.string.fbot_process_command
    "process" -> RuntimeIconName.Play to R.string.fbot_process_process
    "host" -> RuntimeIconName.Phone to R.string.fbot_process_host
    "download" -> RuntimeIconName.Download to R.string.fbot_process_download
    "memory" -> RuntimeIconName.Bookmark to R.string.fbot_process_memory
    "plan" -> RuntimeIconName.Checklist to R.string.fbot_process_plan
    "scratchpad" -> RuntimeIconName.StickyNote to R.string.fbot_process_scratchpad
    "history_search" -> RuntimeIconName.Search to R.string.fbot_process_search
    "build_script" -> RuntimeIconName.Hammer to R.string.fbot_process_build
    "subagent" -> RuntimeIconName.Bot to R.string.fbot_process_subagent
    "compress" -> RuntimeIconName.Compress to R.string.fbot_process_compress
    "ask_user", "ask_user_questions" -> RuntimeIconName.Chat to R.string.fbot_process_question
    "render_surface" -> RuntimeIconName.Palette to R.string.fbot_process_surface
    else -> RuntimeIconName.Extension to null
}

internal fun groupToolIcon(name: String): RuntimeIconName = groupToolPresentation(name).first

@Composable
internal fun groupToolTitle(name: String): String = groupToolPresentation(name).second?.let { stringResource(it) } ?: name
