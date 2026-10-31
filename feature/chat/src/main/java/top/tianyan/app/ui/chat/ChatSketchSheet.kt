package top.tianyan.app.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeTextButton

private data class SketchStroke(val points: List<Offset>)

/**
 * 草图绘制弹窗（参考 rikkahub Sketch）：手指绘制 → 导出 PNG → 以图片附件进输入框。
 * 白纸深墨，导出前按位图尺寸等比放大笔迹。
 */
@Composable
internal fun ChatSketchSheet(
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<SketchStroke>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    ChatBottomSheet(
        onDismissRequest = onDismiss,
        title = { Text("草图", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    strokes.add(SketchStroke(listOf(offset)))
                                },
                                onDrag = { change, _ ->
                                    if (strokes.isNotEmpty()) {
                                        val last = strokes.removeAt(strokes.size - 1)
                                        strokes.add(SketchStroke(last.points + change.position))
                                    }
                                },
                            )
                        },
                ) {
                    val strokeColor = Color(0xFF1F2430)
                    strokes.forEach { stroke ->
                        if (stroke.points.size < 2) return@forEach
                        val path = Path().apply {
                            moveTo(stroke.points.first().x, stroke.points.first().y)
                            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path,
                            strokeColor,
                            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeTextButton(
                        onClick = { strokes.clear() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清空")
                    }
                    RuntimeButton(
                        onClick = {
                            if (canvasSize.width <= 0 || strokes.isEmpty()) return@RuntimeButton
                            val uri = exportSketch(context, strokes, canvasSize) ?: return@RuntimeButton
                            onConfirm(uri)
                        },
                        enabled = strokes.isNotEmpty() && canvasSize.width > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("添加为附件")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 把笔迹按 1080 宽度等比放大画进 PNG，落在 cacheDir，进程内 file:// 直读 */
private fun exportSketch(
    context: android.content.Context,
    strokes: List<SketchStroke>,
    canvasSize: IntSize,
): Uri? = runCatching {
    val width = 1080
    val height = (width.toFloat() / canvasSize.width * canvasSize.height).toInt().coerceAtLeast(1)
    val scale = width.toFloat() / canvasSize.width
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#1F2430")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5f * scale
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val path = android.graphics.Path().apply {
            moveTo(stroke.points.first().x * scale, stroke.points.first().y * scale)
            stroke.points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
        }
        canvas.drawPath(path, paint)
    }
    val dir = File(context.cacheDir, "sketches").apply { mkdirs() }
    val file = File(dir, "sketch_${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    Uri.fromFile(file)
}.getOrNull()
