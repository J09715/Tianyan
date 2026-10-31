package top.tianyan.app.harness.bot.schedule

/**
 * 让宿主重新规划下一次定时唤醒。Harness 不直接依赖 WorkManager；
 * 应用层实现负责取消旧任务并按最新 nextRunAt 重新入队。
 */
fun interface BotScheduleWakeup {
    fun request()
}
