package top.tianyan.app.ui.browser.snapshot

import top.tianyan.app.core.browser.PageSnapshot

data class SnapshotSheetState(
    val url: String,
    val title: String,
    val snapshot: PageSnapshot,
)
