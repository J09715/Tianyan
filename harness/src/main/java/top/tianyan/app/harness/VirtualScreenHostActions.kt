package top.tianyan.app.harness

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.datastore.AgentPreferences
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.runtime.gui.GuiKey
import top.tianyan.app.runtime.gui.GuiPrimitive
import top.tianyan.app.runtime.gui.ScrollDirection
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.tianyan.app.runtime.virtualdisplay.VirtualScreenToolkit
import kotlinx.coroutines.delay

/**
 * host 工具里 virtual_screen_* 的执行体。从 [ToolExecutor] 拆出来，避免那个文件继续涨过行数棘轮。
 */
internal class VirtualScreenHostActions(
    private val coordinator: VirtualDisplayCoordinator?,
    private val toolkit: VirtualScreenToolkit?,
    private val packageNamePattern: Regex,
    private val requireHostIdentifier: (JsonObject, String, Regex) -> String,
    private val requireString: (JsonObject, String) -> String,
    private val requireInt: (JsonObject, String) -> Int,
    private val optionalLong: (JsonObject, String, Long, Long, Long) -> Long,
    private val optionalSession: (JsonObject) -> String,
    private val providerClient: ProviderClient? = null,
    private val preferences: AgentPreferences? = null,
    private val attachImage: (String) -> Unit = {},
    private val services: PhoneAgentServices? = null,
    private val canInject: () -> Boolean = { true },
) {
    suspend fun execute(action: String, args: JsonObject): Pair<Boolean, String> = when (action) {
        "virtual_screen_ensure" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            val displayId = coordinator.ensureVirtualDisplay(session)
            if (displayId == null) {
                false to "虚拟屏创建失败（session=$session）：Shower 服务未启动或建屏失败，详见 runtime.log 中的 [Shower] 日志（不一定是授权问题）"
            } else {
                val hidden = coordinator.reveal(session, "等待打开应用")
                true to "虚拟屏已就绪：session=$session displayId=$displayId。" +
                    (hidden ?: "悬浮窗已弹出。刚建好的屏上没有应用，画面是黑的。") +
                    "用 virtual_screen_launch 打开应用后才会有画面。" +
                    "多步操作交给 virtual_screen_task；只有要亲自看画面时才截图。" +
                    "点击坐标是 0–1000 相对位置，打字用 virtual_screen_input_text"
            }
        }
        "virtual_screen_launch" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            val packageName = requireHostIdentifier(args, "package", packageNamePattern)
            if (!canInject()) return false to "操作已中止：虚拟屏任务暂停或已被人工介入"
            if (coordinator.getDisplayId(session) == null &&
                coordinator.ensureVirtualDisplay(session) == null
            ) {
                return false to "虚拟屏创建失败（session=$session）：Shower 服务未启动或建屏失败，详见 runtime.log 中的 [Shower] 日志（不一定是授权问题）"
            }
            if (!canInject()) return false to "应用启动已中止：任务暂停或已被人工介入"
            val res = coordinator.launchApp(session, packageName)
            if (res) {
                val hidden = coordinator.reveal(session, "启动 $packageName")
                true to "已在虚拟屏启动应用：$packageName（session=$session " +
                    "displayId=${coordinator.getDisplayId(session)}）。" +
                    (hidden ?: "悬浮窗上能看到这个应用，标题栏是当前步骤。")
            } else {
                coordinator.reveal(session, "启动失败 $packageName")
                false to "虚拟屏启动应用失败：$packageName（检查包名是否为已安装应用）"
            }
        }
        "virtual_screen_screenshot" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            if (coordinator.getDisplayId(session) == null) {
                return false to "虚拟屏未创建（session=$session）：先调用 virtual_screen_ensure"
            }
            val hiddenShot = coordinator.reveal(session, "正在截图")
            val png = coordinator.requestScreenshot(session)
                ?: return false to "虚拟屏截图失败（session=$session）：screencap/Binder 通道均不可用，详见 runtime.log 的 [Shower] 日志"
            val (width, height) = pngDimensions(png)
            val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
            attachImage(ImagePayloadCompressor.downscaleDataUrl(dataUrl))
            val saved = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { targetPath ->
                runCatching {
                    val file = File(targetPath)
                    file.parentFile?.mkdirs()
                    file.writeBytes(png)
                    targetPath
                }.getOrNull()
            }
            true to buildString {
                append("虚拟屏截图已随本条结果附上")
                if (width > 0 && height > 0) append("，物理分辨率 ${width}×${height}")
                append("，${png.size} 字节。")
                append("点击、双击、长按、滑动用 0–1000 相对坐标，左上角是 0,0，不要用像素，也不要按截图缩放换算。")
                append("输入文字用 virtual_screen_input_text。paste_text 和 screen_input_text 打到主屏焦点，会把虚拟屏里的应用切走。")
                if (saved != null) append(" 文件副本：$saved。")
                hiddenShot?.let { append(' ').append(it) }
            }
        }
        "virtual_screen_input_text", "virtual_screen_set_text" -> {
            val toolkit = toolkit ?: return false to "未初始化虚拟屏工具"
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            if (coordinator.getDisplayId(session) == null) {
                return false to "虚拟屏未创建（session=$session）：先调用 virtual_screen_ensure"
            }
            val text = requireString(args, "text")
            val result = if (action == "virtual_screen_set_text") toolkit.setText(session, text, canInject)
                else toolkit.execute(session, GuiPrimitive.PasteText(text), canInject)
            val hiddenStep = coordinator.reveal(session, result.message)
            result.success to if (hiddenStep == null) result.message else "${result.message} $hiddenStep"
        }
        "virtual_screen_click",
        "virtual_screen_double_click",
        "virtual_screen_long_press",
        "virtual_screen_swipe",
        "virtual_screen_scroll",
        "virtual_screen_key",
        -> {
            val toolkit = toolkit ?: return false to "未初始化虚拟屏工具"
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            val primitive = when (action) {
                "virtual_screen_click" -> {
                    val (x, y) = relativePoint(session, requireInt(args, "x"), requireInt(args, "y"))
                        ?: return false to relativePointUnavailable(session)
                    GuiPrimitive.Tap(x, y)
                }
                "virtual_screen_double_click" -> {
                    val (x, y) = relativePoint(session, requireInt(args, "x"), requireInt(args, "y"))
                        ?: return false to relativePointUnavailable(session)
                    GuiPrimitive.DoubleTap(x, y)
                }
                "virtual_screen_long_press" -> {
                    val (x, y) = relativePoint(session, requireInt(args, "x"), requireInt(args, "y"))
                        ?: return false to relativePointUnavailable(session)
                    GuiPrimitive.LongPress(
                        x = x,
                        y = y,
                        durationMs = optionalLong(args, "duration_ms", 800L, 200L, 5_000L),
                    )
                }
                "virtual_screen_swipe" -> {
                    val (x1, y1) = relativePoint(session, requireInt(args, "x1"), requireInt(args, "y1"))
                        ?: return false to relativePointUnavailable(session)
                    val (x2, y2) = relativePoint(session, requireInt(args, "x2"), requireInt(args, "y2"))
                        ?: return false to relativePointUnavailable(session)
                    GuiPrimitive.Swipe(
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        durationMs = optionalLong(args, "duration_ms", 300L, 50L, 5_000L),
                    )
                }
                "virtual_screen_scroll" -> GuiPrimitive.Scroll(
                    direction = when (args["direction"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                        "up" -> ScrollDirection.UP
                        "down" -> ScrollDirection.DOWN
                        "left" -> ScrollDirection.LEFT
                        "right" -> ScrollDirection.RIGHT
                        else -> return false to "screen_scroll 需要 direction: up/down/left/right"
                    },
                    distanceRatio = args["distance_ratio"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0.45f,
                    durationMs = optionalLong(args, "duration_ms", 350L, 50L, 5_000L),
                )
                else -> {
                    val key = GuiKey.parse(requireString(args, "key"))
                        ?: return false to "未知按键：支持 back/home/recents/enter/delete/paste/power"
                    GuiPrimitive.Key(key)
                }
            }
            val result = toolkit.execute(session, primitive, canInject)
            val hiddenStep = coordinator.reveal(session, result.message)
            result.success to if (hiddenStep == null) result.message else "${result.message} $hiddenStep"
        }
        "virtual_screen_close" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            coordinator.closeSession(session)
            true to "已关闭虚拟屏会话：$session"
        }
        "virtual_screen_show" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val session = optionalSession(args)
            if (coordinator.getDisplayId(session) == null &&
                coordinator.ensureVirtualDisplay(session) == null
            ) {
                return false to "虚拟屏创建失败（session=$session）：Shower 服务未启动或建屏失败，详见 runtime.log 中的 [Shower] 日志（不一定是授权问题）"
            }
            if (coordinator.showOverlay(session)) {
                coordinator.reveal(session, "正在显示画面")
                true to "已显示虚拟屏实时悬浮窗（session=$session）：用户可观看画面并直接触摸干预，标题栏是当前步骤"
            } else {
                false to "悬浮窗权限未授予：请引导用户在系统设置中允许「显示在其他应用上层」后重试"
            }
        }
        "virtual_screen_task" -> {
            val client = providerClient ?: return false to "手机操作模型还没接上。"
            val prefs = preferences ?: return false to "手机操作模型还没接上。"
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            val toolkit = toolkit ?: return false to "未初始化虚拟屏工具"
            val session = optionalSession(args)
            val goal = requireString(args, "goal")
            val packageName = args["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val maxSteps = optionalLong(args, "max_steps", 12L, 1L, 20L).toInt()
            val workflowName = args["workflow_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(100)
            PhoneAgentPilot(client, prefs, coordinator, toolkit, services).run(session, goal, packageName, maxSteps, workflowName)
        }
        "virtual_screen_wait" -> {
            val millis = optionalLong(args, "duration_ms", 1000L, 0L, 600_000L)
            delay(millis)
            true to "已等待 ${millis}ms"
        }
        "virtual_screen_hide" -> {
            val coordinator = coordinator ?: return false to "未初始化虚拟屏协调器"
            coordinator.hideOverlay()
            true to "已隐藏虚拟屏悬浮窗（虚拟屏会话不受影响，仍可继续操作）"
        }
        else -> error("virtual_screen action 未登记：$action")
    }

    /**
     * 虚拟屏指针坐标是 0–1000 的相对位置，和模型看到的缩放截图无关。
     * 尺寸未知时返回 null，避免把相对坐标当成物理像素打偏。
     */
    private fun relativePoint(session: String, x: Int, y: Int): Pair<Int, Int>? {
        require(x in 0..1000 && y in 0..1000) { "虚拟屏坐标必须在 0–1000" }
        val size = coordinator?.getVideoSize(session) ?: return null
        return phoneAgentPoint(x, size.first) to phoneAgentPoint(y, size.second)
    }

    private fun relativePointUnavailable(session: String): String =
        "虚拟屏尺寸未知（session=$session），无法把 0–1000 坐标换算成像素。先 virtual_screen_ensure，等画面出现后再操作。"

    companion object {
        fun handles(action: String): Boolean = action in ACTIONS

        /** PNG IHDR 里的宽高。不是 PNG 或头部不完整时返回 0×0。 */
        internal fun pngDimensions(png: ByteArray): Pair<Int, Int> {
            if (png.size < PNG_IHDR_END) return 0 to 0
            if (png[0] != PNG_SIGNATURE_0 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte() || png[3] != 'G'.code.toByte()) {
                return 0 to 0
            }
            return readBe32(png, PNG_WIDTH_OFFSET) to readBe32(png, PNG_HEIGHT_OFFSET)
        }

        private fun readBe32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)

        private const val PNG_SIGNATURE_0 = 0x89.toByte()
        private const val PNG_WIDTH_OFFSET = 16
        private const val PNG_HEIGHT_OFFSET = 20
        private const val PNG_IHDR_END = 24

        private val ACTIONS = setOf(
            "virtual_screen_ensure",
            "virtual_screen_launch",
            "virtual_screen_screenshot",
            "virtual_screen_click",
            "virtual_screen_double_click",
            "virtual_screen_long_press",
            "virtual_screen_swipe",
            "virtual_screen_scroll",
            "virtual_screen_key",
            "virtual_screen_input_text",
            "virtual_screen_set_text", "virtual_screen_wait",
            "virtual_screen_close",
            "virtual_screen_show",
            "virtual_screen_hide",
            "virtual_screen_task",
        )
    }
}
