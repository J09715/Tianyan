package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.AccessibilityAttributes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Basic Catalog Image：按实现指南映射 fit / variant，只加载 http/https。
 */
internal class TianyanImageComponent : A2uiBasicCatalogV1.Image {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        url: String,
        description: String?,
        fit: A2uiBasicCatalogV1.Image.Fit,
        variant: A2uiBasicCatalogV1.Image.Variant,
        accessibility: AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val allowed = TianyanA2uiMediaPolicy.httpUrlOrNull(url)
        if (allowed == null) {
            TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_media_blocked), modifier)
            return
        }
        val scale = when {
            variant == A2uiBasicCatalogV1.Image.Variant.Header -> ContentScale.Crop
            fit == A2uiBasicCatalogV1.Image.Fit.Contain -> ContentScale.Fit
            fit == A2uiBasicCatalogV1.Image.Fit.Cover -> ContentScale.Crop
            fit == A2uiBasicCatalogV1.Image.Fit.None -> ContentScale.None
            fit == A2uiBasicCatalogV1.Image.Fit.ScaleDown -> ContentScale.Inside
            else -> ContentScale.FillBounds
        }
        val frame = when (variant) {
            A2uiBasicCatalogV1.Image.Variant.Icon -> Modifier.size(24.dp)
            A2uiBasicCatalogV1.Image.Variant.Avatar -> Modifier.size(40.dp)
            A2uiBasicCatalogV1.Image.Variant.SmallFeature -> Modifier.size(100.dp)
            A2uiBasicCatalogV1.Image.Variant.MediumFeature -> Modifier.fillMaxWidth().height(200.dp)
            A2uiBasicCatalogV1.Image.Variant.LargeFeature -> Modifier.fillMaxWidth().height(400.dp)
            A2uiBasicCatalogV1.Image.Variant.Header -> Modifier.fillMaxWidth().height(200.dp)
        }
        val shape = if (variant == A2uiBasicCatalogV1.Image.Variant.Avatar) {
            CircleShape
        } else {
            MaterialTheme.shapes.small
        }
        var phase by remember(allowed) { mutableIntStateOf(0) }
        val context = LocalContext.current
        Box(
            modifier.then(frame).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(allowed).crossfade(true).build(),
                contentDescription = description ?: accessibility?.label,
                contentScale = scale,
                onSuccess = { phase = 1 },
                onError = { phase = 2 },
                modifier = Modifier.matchParentSize(),
            )
            when (phase) {
                0 -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                2 -> TianyanA2uiMediaNotice(stringResource(R.string.fa2ui_image_failed), Modifier.matchParentSize())
            }
        }
    }
}
