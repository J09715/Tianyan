package top.tianyan.app.ui.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarArt
import top.tianyan.app.core.model.bot.PixelAvatarRecipe
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun PixelAvatarDesigner(
    seed: String,
    defaultSeed: String,
    colorHex: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by rememberSaveable(seed) {
        mutableStateOf((PixelAvatarRecipe.decode(seed) ?: PixelAvatarRecipe.fromName(defaultSeed)).encode())
    }
    var previewState by rememberSaveable { mutableStateOf(BotAvatarState.IDLE) }
    val recipe = requireNotNull(PixelAvatarRecipe.decode(draft))
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_pixel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BotAvatar(
                        seed = draft,
                        colorHex = colorHex,
                        size = 112.dp,
                        state = previewState,
                        modifier = Modifier.testTag("pixel-avatar-preview"),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RuntimeTextButton(onClick = { draft = PixelAvatarRecipe.random().encode() }) {
                            Text(stringResource(R.string.fbot_pixel_random))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeFilterChip(
                                selected = previewState == BotAvatarState.IDLE,
                                onClick = { previewState = BotAvatarState.IDLE },
                                label = { Text(stringResource(R.string.fbot_pixel_state_idle)) },
                            )
                            RuntimeFilterChip(
                                selected = previewState == BotAvatarState.THINKING,
                                onClick = { previewState = BotAvatarState.THINKING },
                                label = { Text(stringResource(R.string.fbot_pixel_state_thinking)) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeFilterChip(
                                selected = previewState == BotAvatarState.EXECUTING,
                                onClick = { previewState = BotAvatarState.EXECUTING },
                                label = { Text(stringResource(R.string.fbot_pixel_state_executing)) },
                            )
                            RuntimeFilterChip(
                                selected = previewState == BotAvatarState.BLOCKED,
                                onClick = { previewState = BotAvatarState.BLOCKED },
                                label = { Text(stringResource(R.string.fbot_pixel_state_blocked)) },
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PixelChoices(R.string.fbot_pixel_kind, R.array.fbot_pixel_kinds, recipe.kind) { draft = recipe.copy(kind = it).encode() }
                    PixelChoices(R.string.fbot_pixel_hair, R.array.fbot_pixel_hairs, recipe.hair, enabled = recipe.kind == 0 || recipe.kind == 3) { draft = recipe.copy(hair = it).encode() }
                    PixelChoices(R.string.fbot_pixel_eyes, R.array.fbot_pixel_eye_styles, recipe.eyes) { draft = recipe.copy(eyes = it).encode() }
                    PixelChoices(R.string.fbot_pixel_mouth, R.array.fbot_pixel_mouth_styles, recipe.mouth) { draft = recipe.copy(mouth = it).encode() }
                    PixelChoices(R.string.fbot_pixel_outfit, R.array.fbot_pixel_outfits, recipe.outfit) { draft = recipe.copy(outfit = it).encode() }
                    PixelChoices(R.string.fbot_pixel_accessory, R.array.fbot_pixel_accessories, recipe.accessory) { draft = recipe.copy(accessory = it).encode() }
                    PixelChoices(R.string.fbot_pixel_skin, R.array.fbot_pixel_skins, recipe.skinColor, colors = PixelAvatarArt.skinColors) { draft = recipe.copy(skinColor = it).encode() }
                    PixelChoices(R.string.fbot_pixel_hair_color, R.array.fbot_pixel_hair_colors, recipe.hairColor, colors = PixelAvatarArt.hairColors) { draft = recipe.copy(hairColor = it).encode() }
                    PixelChoices(R.string.fbot_pixel_outfit_color, R.array.fbot_pixel_outfit_colors, recipe.outfitColor, colors = PixelAvatarArt.outfitColors) { draft = recipe.copy(outfitColor = it).encode() }
                    PixelChoices(R.string.fbot_pixel_backdrop, R.array.fbot_pixel_backdrops, recipe.backdrop, colors = PixelAvatarArt.backdropColors) { draft = recipe.copy(backdrop = it).encode() }
                }
            }
        },
        confirmButton = {
            RuntimeButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.fbot_pixel_apply)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}

@Composable
private fun PixelChoices(
    label: Int,
    options: Int,
    selected: Int,
    enabled: Boolean = true,
    colors: List<Int>? = null,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            stringArrayResource(options).forEachIndexed { index, name ->
                RuntimeFilterChip(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = colors?.let { palette -> {
                        Box(Modifier.size(16.dp).background(Color(palette[index]), CircleShape))
                    } },
                    modifier = Modifier.testTag("pixel-choice-$label-$index"),
                )
            }
        }
    }
}
