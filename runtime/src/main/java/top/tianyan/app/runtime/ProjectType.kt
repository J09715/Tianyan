package top.tianyan.app.runtime

enum class ProjectType {
    ANDROID, FLUTTER, REVERSE, GENERAL, TIANYAN_PLUGIN,

    /**
     * 红队演练。
     *
     * 与其他类型同层，不是独立的旁路功能：新建会话时选它，该会话就绑定目标与作战状态，
     * harness 侧据此切到红队提示词与工具集，chat 侧据此展示红队作战面板。
     */
    RED_TEAM;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK 逆向"
            GENERAL -> "通用"
            TIANYAN_PLUGIN -> "天衍本地插件"
            RED_TEAM -> "红队演练"
        }
}
