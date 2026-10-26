package top.tianyan.app.ui.theme

import top.tianyan.app.core.model.ThemePaletteStyle

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内存全局缓存的壁纸种子色，避免 Composable 重构时发生闪烁
 */
@Volatile
private var cachedWallpaperSeed: Color? = null

/**
 * 从系统中提取当前壁纸的主色调种子（Seed Color）。
 *
 * 容灾策略：
 * 1. 优先从 [WallpaperManager.getDrawable] 获取真实壁纸位图并采样分析（彻底规避 Vivo OriginOS / 小米 HyperOS 等定制 ROM 将系统动态色锁死为静态 preset 的问题）；
 * 2. 若 Drawable 受限无法读取，回退至 [WallpaperManager.getWallpaperColors]（API 27+）；
 * 3. 发生任何异常时安全返回 null。
 */
fun extractWallpaperSeedColor(context: Context): Color? {
    val wallpaperManager = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return null

    // 1. 优先尝试从真实壁纸 Drawable 获取（天衍已具 MANAGE_EXTERNAL_STORAGE 特权）
    val colorFromDrawable = runCatching {
        val drawable = wallpaperManager.drawable
        if (drawable != null) {
            extractColorFromDrawable(drawable)
        } else {
            null
        }
    }.getOrNull()

    if (colorFromDrawable != null) {
        cachedWallpaperSeed = colorFromDrawable
        return colorFromDrawable
    }

    // 2. 备用：从系统 WallpaperColors 提取
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val sysColors = runCatching {
            wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        val primary = sysColors?.primaryColor
        if (primary != null) {
            val color = Color(primary.toArgb())
            if (color != Color.Transparent && color.alpha > 0.5f) {
                cachedWallpaperSeed = color
                return color
            }
        }
    }

    return cachedWallpaperSeed
}

/**
 * 将壁纸 Drawable 降采样绘制为 64x64 位图，并提取最具表现力的主色调
 */
private fun extractColorFromDrawable(drawable: Drawable): Color? {
    val targetWidth = 64
    val targetHeight = 64
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, targetWidth, targetHeight)
    drawable.draw(canvas)

    // A. 优先利用 Android 系统官方算法分析位图
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val wpColors = runCatching { WallpaperColors.fromBitmap(bitmap) }.getOrNull()
        val primary = wpColors?.primaryColor
        if (primary != null) {
            val c = Color(primary.toArgb())
            if (c != Color.Transparent && c.alpha > 0.5f) {
                bitmap.recycle()
                return c
            }
        }
    }

    // B. 容灾：遍历位图像素直方图，寻找饱和度适中、色相明确的主色
    val pixels = IntArray(targetWidth * targetHeight)
    bitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    bitmap.recycle()

    val hsl = FloatArray(3)
    var bestColor = 0
    var maxScore = -1f

    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xff
        if (alpha < 128) continue
        ColorUtils.colorToHSL(pixel, hsl)
        val s = hsl[1]
        val l = hsl[2]
        // 过滤极黑与极白
        if (l < 0.10f || l > 0.95f) continue
        // 评分：彩度鲜明、明度舒适的像素得分高
        val score = s * (1f - kotlin.math.abs(l - 0.5f) * 1.5f).coerceAtLeast(0.1f)
        if (score > maxScore) {
            maxScore = score
            bestColor = pixel
        }
    }

    return if (bestColor != 0) Color(bestColor) else null
}

/**
 * 监听系统壁纸变动并提供动态派生的 Material 3 色板。
 *
 * 取色优先级：
 * 1. 系统 Monet 色板（正常 ROM 直接透传，含系统对比度偏好，最「原汁原味」）；
 * 2. 检测到 ROM 把动态色锁死为预置色（系统主色与壁纸种子色相脱节）或系统不支持时，
 *    用官方 HCT 算法从壁纸种子派生 [generateM3ColorScheme]；
 * 3. 均不可得时回退玄同静态色板 [fallback]。
 */
@Composable
fun rememberWallpaperDynamicColorScheme(
    darkTheme: Boolean,
    systemScheme: ColorScheme?,
    fallback: ColorScheme,
    style: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
): ColorScheme {
    val context = LocalContext.current.applicationContext
    var seedColor by remember { mutableStateOf(cachedWallpaperSeed) }

    DisposableEffect(context) {
        var isDisposed = false
        fun updateSeed() {
            CoroutineScope(Dispatchers.IO).launch {
                val extracted = extractWallpaperSeedColor(context)
                if (!isDisposed && extracted != null && extracted != seedColor) {
                    withContext(Dispatchers.Main) {
                        seedColor = extracted
                    }
                }
            }
        }

        // 初次加载确保刷新
        updateSeed()

        // 1. WallpaperManager colors changed listener
        val colorsListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val listener = WallpaperManager.OnColorsChangedListener { _, which ->
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                    updateSeed()
                }
            }
            val wallpaperManager = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
            wallpaperManager?.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            listener
        } else null

        // 壁纸变化经 WallpaperManager.OnColorsChangedListener 感知（O_MR1+ 覆盖全部 minSdk）；
        // ACTION_WALLPAPER_CHANGED 广播已废弃且不再向三方应用派发，不再注册冗余接收器。

        onDispose {
            isDisposed = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && colorsListener != null) {
                runCatching {
                    WallpaperManager.getInstance(context).removeOnColorsChangedListener(colorsListener)
                }
            }
        }
    }

    val currentSeed = seedColor
    return remember(currentSeed, darkTheme, systemScheme, fallback, style) {
        when {
            style != ThemePaletteStyle.TONAL_SPOT ->
                generateM3ColorScheme(currentSeed ?: systemScheme?.primary ?: fallback.primary, darkTheme, style)
            // 壁纸不可读：优先系统 Monet，其次玄同静态色板
            currentSeed == null -> systemScheme ?: fallback
            // 系统不支持动态取色（API < 31）：HCT 从种子派生
            systemScheme == null -> generateM3ColorScheme(currentSeed, darkTheme)
            // 系统色板被 ROM 锁死为预置色：HCT 从种子派生
            isSystemSchemeColorLocked(systemScheme.primary, currentSeed) ->
                generateM3ColorScheme(currentSeed, darkTheme)
            else -> systemScheme
        }
    }
}
