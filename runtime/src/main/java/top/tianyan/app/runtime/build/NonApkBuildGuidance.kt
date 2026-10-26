package top.tianyan.app.runtime.build

import top.tianyan.app.runtime.ProjectType

internal fun nonApkBuildGuidance(type: ProjectType): String = when (type) {
    ProjectType.TIANYAN_PLUGIN -> "点击工程卡片“一键导入调试”直接打包并打开安装确认；正式发布在终端执行 python3 tools/package_plugin.py。详见 README.md。"
    else -> "通用工程请在天衍终端中执行自定义命令或自定义构建脚本"
}
