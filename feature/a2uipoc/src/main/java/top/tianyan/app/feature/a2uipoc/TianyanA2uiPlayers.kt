package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.AccessibilityAttributes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * Basic Catalog Video / AudioPlayer。播放器铺满宽度，自带进度拖动，离开组合时释放。
 * 默认不自动播放，避免聊天流里多张卡片同时出声。
 */
internal class TianyanVideoComponent : A2uiBasicCatalogV1.Video {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        url: String,
        accessibility: AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val allowed = TianyanA2uiMediaPolicy.httpUrlOrNull(url)
        if (allowed == null) {
            TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_media_blocked), modifier)
            return
        }
        val player = rememberA2uiPlayer(allowed)
        var failed by remember(allowed) { mutableStateOf(false) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        if (failed) {
            TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_video_failed), modifier.fillMaxWidth())
            return
        }
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = true
                    this.player = player
                    contentDescription = accessibility?.label
                }
            },
            update = { view -> view.player = player },
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }
}

internal class TianyanAudioPlayerComponent : A2uiBasicCatalogV1.AudioPlayer {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        url: String,
        description: String?,
        accessibility: AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val allowed = TianyanA2uiMediaPolicy.httpUrlOrNull(url)
        if (allowed == null) {
            TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_media_blocked), modifier)
            return
        }
        val player = rememberA2uiPlayer(allowed)
        var failed by remember(allowed) { mutableStateOf(false) }
        var playing by remember(allowed) { mutableStateOf(false) }
        var position by remember(allowed) { mutableLongStateOf(0L) }
        var duration by remember(allowed) { mutableLongStateOf(0L) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onEvents(player: Player, events: Player.Events) {
                    position = player.currentPosition.coerceAtLeast(0L)
                    val total = player.duration
                    duration = if (total > 0L) total else 0L
                }
                override fun onPlayerError(error: PlaybackException) { failed = true }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        if (failed) {
            TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_audio_failed), modifier.fillMaxWidth())
            return
        }
        val playLabel = stringResource(if (playing) R.string.fa2ui_pause else R.string.fa2ui_play)
        val title = description ?: accessibility?.label ?: allowed
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        modifier = Modifier.semantics { contentDescription = playLabel },
                    ) {
                        RuntimeIcon(
                            if (playing) RuntimeIconName.Stop else RuntimeIconName.Play,
                            Modifier,
                        )
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${formatClock(position)} / ${formatClock(duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                val fraction = if (duration > 0L) position.toFloat() / duration else 0f
                Slider(
                    value = fraction.coerceIn(0f, 1f),
                    onValueChange = { next ->
                        if (duration > 0L) player.seekTo((next * duration).toLong())
                    },
                    enabled = duration > 0L,
                )
            }
        }
    }
}

@Composable
private fun rememberA2uiPlayer(url: String): ExoPlayer {
    val context = LocalContext.current.applicationContext
    return remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = false
            prepare()
        }
    }
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
