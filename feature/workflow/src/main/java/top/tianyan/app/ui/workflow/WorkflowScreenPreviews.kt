package top.tianyan.app.ui.workflow

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 360, heightDp = 800, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun WorkflowScreenPreview() {
    RuntimePreview {
        WorkflowScreen(onBack = { })
    }
}

@FoldablePreviews
@Preview(name = "窄屏 · 大字体", widthDp = 360, heightDp = 800, fontScale = 1.3f, locale = "zh")
@Preview(name = "深色 · English", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun WorkflowEditorPreview() {
    RuntimePreview {
        WorkflowEditorView(
            state = WorkflowEditorUiState(definition = WorkflowDefinition(id = "preview", name = "预览工作流")),
            onSelectNode = { _ -> },
            onMoveNode = { _, _, _ -> },
            onBeginConnection = { _ -> },
            onCancelConnection = { },
            onConnect = { _ -> },
            onAddNode = { _ -> },
            onUpdateNode = { _ -> },
            onUpdateMetadata = { _, _, _, _ -> },
            onRemoveNode = { },
            onUpdateEdge = { _ -> },
            onRemoveEdge = { _ -> },
            onUndo = { },
            onRedo = { },
            onSave = { },
            onAutoLayout = { },
        )
    }
}
