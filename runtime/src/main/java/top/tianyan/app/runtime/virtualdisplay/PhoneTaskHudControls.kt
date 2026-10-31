package top.tianyan.app.runtime.virtualdisplay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 悬浮窗的原生 View 控件，与视频挂载共用生命周期。 */
internal fun phoneTaskHudControls(
    context: Context, registry: PhoneTaskRegistry, session: String, scope: CoroutineScope,
): LinearLayout {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun button(label: String) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        row.addView(this)
    }
    val pause = button("暂停")
    val stop = button("停止")
    pause.setOnClickListener {
        registry.get(session)?.let { task ->
            if (task.state.value == PhoneTaskState.PAUSED) task.resume() else task.pause()
        }
    }
    stop.setOnClickListener { registry.cancel(session) }
    scope.launch {
        while (true) {
            val state = registry.get(session)?.state?.value
            val running = state == PhoneTaskState.RUNNING || state == PhoneTaskState.PAUSED
            pause.text = if (state == PhoneTaskState.PAUSED) "继续" else "暂停"
            pause.contentDescription = pause.text
            pause.isEnabled = running
            stop.isEnabled = running
            pause.alpha = if (running) 1f else 0.4f
            stop.alpha = pause.alpha
            delay(200)
        }
    }
    return row
}
