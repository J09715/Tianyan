package top.tianyan.app.runtime

enum class ProjectType {
    ANDROID, FLUTTER, REVERSE, GENERAL, TIANYAN_PLUGIN;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK 逆向"
            GENERAL -> "通用"
            TIANYAN_PLUGIN -> "天衍本地插件"
        }
}
