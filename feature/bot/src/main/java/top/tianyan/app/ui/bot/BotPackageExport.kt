package top.tianyan.app.ui.bot

import android.content.Context
import java.io.File
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.harness.bot.archive.BotPackageService

/** 从 BotDetailViewModel 抽出以控制文件尺寸：Bot 导出包落盘。 */
internal suspend fun exportBotPackage(
    context: Context,
    packageService: BotPackageService,
    bot: Bot,
): String {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val dir = File(base, "bot-exports").apply { mkdirs() }
    val safeName = bot.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
    val target = File(dir, "$safeName-${System.currentTimeMillis()}.zip")
    packageService.exportBot(bot.id, target)
    return "已导出到 ${target.absolutePath}"
}
