package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import java.io.File
import top.tianyan.app.core.model.ChatIdentityStyle

val LocalChatIdentity = staticCompositionLocalOf { ChatIdentityStyle() }

@Composable
fun RuntimeChatIdentityAvatar(size: Dp, style: ChatIdentityStyle = LocalChatIdentity.current) {
    val context = LocalContext.current
    val name = style.normalized().imageName
    val file = remember(context, name) { name?.let { File(File(context.filesDir, ChatIdentityStyle.IMAGE_DIRECTORY), it) } }
    var loaded by remember(file) { mutableStateOf(false) }
    Box(Modifier.size(size).clip(MaterialTheme.shapes.small)) {
        if (!loaded) TianyanBrandBadge(size)
        if (file != null) AsyncImage(model = file, contentDescription = null,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = if (loaded) 1f else 0f },
            contentScale = ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
