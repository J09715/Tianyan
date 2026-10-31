package top.tianyan.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** Preserve history reading when the keyboard opens; recheck user intent after the IME relayout. */
@Composable
internal fun ChatImeFollowLatest(listState: LazyListState, imeVisible: Boolean, followLatest: Boolean) {
    val following by rememberUpdatedState(followLatest)
    LaunchedEffect(imeVisible, listState) {
        if (!imeVisible || !following) return@LaunchedEffect
        delay(80)
        if (following && !listState.isScrollInProgress) listState.safeScrollToLastItem(animated = true)
    }
}
