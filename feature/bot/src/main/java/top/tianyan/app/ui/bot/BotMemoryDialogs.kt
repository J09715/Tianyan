package top.tianyan.app.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.bot.BotMemoryDiff
import top.tianyan.app.core.model.bot.BotMemoryDocument
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton

/** Kept in the screen ViewModel: large Markdown drafts must not be written into the Activity Bundle. */
internal class BotMemoryEditorState {
    val draft = mutableStateOf<String?>(null)
    val revision = mutableStateOf<String?>(null)
}

@Composable
internal fun BotMemoryEditorDialog(path: String, onDismiss: () -> Unit,
    state: BotMemoryEditorState = remember(path) { BotMemoryEditorState() },
    load: suspend () -> BotMemoryDocument, save: suspend (BotMemoryDocument, String) -> Unit) {
    var document by remember(path) { mutableStateOf<BotMemoryDocument?>(null) }
    var draft by state.draft
    var revision by state.revision
    var error by remember(path) { mutableStateOf<String?>(null) }
    var reload by remember(path) { mutableIntStateOf(0) }
    var loading by remember(path) { mutableStateOf(true) }
    var saving by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, reload) {
        loading = true
        try {
            val loaded = load()
            document = loaded
            if (draft == null) { draft = loaded.content; revision = loaded.revision }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取失败" }
        finally { loading = false }
    }
    RuntimeAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.fbot_memory_edit)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(path, style = MaterialTheme.typography.bodyMedium)
                if (loading) RuntimeCircularProgressIndicator()
                if (draft != null) RuntimeFilledTextField(value = draft.orEmpty(), onValueChange = { draft = it },
                    enabled = !saving && !loading, label = { Text(stringResource(R.string.fbot_memory_body)) }, minLines = 8, maxLines = 18,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                RuntimeTextButton(enabled = !saving && !loading, onClick = {
                    draft = null; revision = null; document = null; reload++
                }) { Text(stringResource(R.string.fbot_memory_reload)) }
            }
        },
        confirmButton = {
            RuntimeButton(enabled = !saving && !loading && document != null && draft != null && revision != null, onClick = {
                val source = document?.copy(revision = revision ?: return@RuntimeButton) ?: return@RuntimeButton
                saving = true
                scope.launch {
                    try { save(source, draft.orEmpty()); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "保存失败" }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.fbot_save)) }
        },
        dismissButton = { RuntimeTextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.fbot_close)) } },
    )
}

@Composable
internal fun BotMemoryDiffDialog(hash: String, onDismiss: () -> Unit, load: suspend () -> BotMemoryDiff) {
    var diff by remember(hash) { mutableStateOf<BotMemoryDiff?>(null) }
    var error by remember(hash) { mutableStateOf<String?>(null) }
    var attempt by remember(hash) { mutableIntStateOf(0) }
    LaunchedEffect(hash, attempt) {
        error = null
        try { diff = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取失败" }
    }
    RuntimeAlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_memory_diff) + " · " + hash.take(8)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val shown = diff
                if (shown == null && error == null) RuntimeCircularProgressIndicator()
                if (shown != null) {
                    Text(stringResource(R.string.fbot_memory_diff_hint), style = MaterialTheme.typography.bodySmall)
                    if (shown.truncated) Text(stringResource(R.string.fbot_memory_diff_truncated), color = MaterialTheme.colorScheme.error)
                    SelectionContainer {
                        Text(shown.text.ifEmpty { stringResource(R.string.fbot_memory_diff_empty) },
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    RuntimeTextButton(onClick = { attempt++ }) { Text(stringResource(R.string.fbot_memory_retry)) }
                }
            }
        },
        confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_close)) } },
    )
}
