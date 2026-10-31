package top.tianyan.app.core.model.workflow

/** Persisted IDs share the HOST tool's virtual-screen primitives. Coordinates are always 0–1000. */
object VirtualScreenWorkflowActions {
    private fun field(key: String, label: String, hint: String = "", required: Boolean = false) =
        HostWorkflowField(key, label, hint, required, multiline = key == "text")

    private fun action(id: String, label: String, description: String, vararg fields: HostWorkflowField) =
        HostWorkflowActionDef(
            id, label, "虚拟屏", HostWorkflowPrivilege.NONE, description,
            listOf(field("session", "虚拟屏会话", "留空按本次工作流隔离；同一次运行的步骤共用屏幕")) + fields +
                field("wait_ms", "操作后等待（毫秒）", "默认 0；0–600000，可用变量"),
        )

    val all = listOf(
        action("virtual_screen_ensure", "创建虚拟屏", "创建独立虚拟屏，需要 Shower 服务可用。"),
        action("virtual_screen_launch", "虚拟屏打开应用", "按包名启动应用，不调用模型。",
            field("package", "应用包名", "例如 com.android.settings 或 \${PACKAGE}", true)),
        action("virtual_screen_click", "虚拟屏点击", "0–1000 相对坐标，与截图缩放无关。",
            field("x", "X（0–1000）", required = true), field("y", "Y（0–1000）", required = true)),
        action("virtual_screen_double_click", "虚拟屏双击", "0–1000 相对坐标。",
            field("x", "X（0–1000）", required = true), field("y", "Y（0–1000）", required = true)),
        action("virtual_screen_long_press", "虚拟屏长按", "坐标和按住时长可分别调整。",
            field("x", "X（0–1000）", required = true), field("y", "Y（0–1000）", required = true),
            field("duration_ms", "按住时长（毫秒）", "默认 800；200–5000")),
        action("virtual_screen_swipe", "虚拟屏滑动", "起终点均为 0–1000，手势时长与操作后等待分开。",
            field("x1", "起点 X（0–1000）", required = true), field("y1", "起点 Y（0–1000）", required = true),
            field("x2", "终点 X（0–1000）", required = true), field("y2", "终点 Y（0–1000）", required = true),
            field("duration_ms", "手势时长（毫秒）", "默认 300；50–5000")),
        action("virtual_screen_input_text", "虚拟屏追加输入", "追加到当前焦点，保留文本前后空白。",
            field("text", "输入内容", "支持多行或 \${TEXT_1}", true)),
        action("virtual_screen_set_text", "虚拟屏替换输入", "发送全选和粘贴按键；目标控件需支持，空文本用于清空。",
            field("text", "替换内容", "支持多行或 \${TEXT_1}")),
        action("virtual_screen_key", "虚拟屏按键", "向虚拟屏发送按键。",
            field("key", "按键", "back / home / enter / delete / paste / recents", true)),
        action("virtual_screen_wait", "虚拟屏等待", "只等待，不调用模型。",
            field("duration_ms", "等待时长（毫秒）", "默认 1000；0–600000", true)),
        action("virtual_screen_close", "关闭虚拟屏", "释放这次运行的屏幕。"),
    )

    fun template(): WorkflowDefinition = PhoneOperationWorkflow.create(
        id = "virtual_screen_template", name = "虚拟屏操作模板",
        operations = listOf(
            PhoneWorkflowOperation("virtual_screen_launch", mapOf("package" to "\${PACKAGE}"), 900),
        ),
    ).copy(
        isBuiltin = true,
        description = "复制后添加点击、滑动、输入和等待节点；每个步骤可独立微调。固定步骤重放不调用模型，运行前请确认应用页面。",
        defaultVariables = mapOf("PACKAGE" to "com.android.settings"),
    )
}
