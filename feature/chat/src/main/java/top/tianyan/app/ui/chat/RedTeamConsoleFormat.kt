package top.tianyan.app.ui.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台的时间显示。
 *
 * 事实库里存的是 epoch 毫秒（本移植与上游的一处有意分歧：上游存 ISO 字符串）。
 * 面板上「首见」只需要到天，详情里的采集时间要到秒——同一个时间戳两种粒度，
 * 所以两个函数，而不是让调用方各自截字符串。
 */
internal fun formatEpochTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

internal fun formatEpochDay(timestamp: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
