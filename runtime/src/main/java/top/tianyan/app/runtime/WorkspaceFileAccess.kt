package top.tianyan.app.runtime

import java.io.File
import top.tianyan.app.core.common.result.AppResult

/**
 * 文件检查与宿主路径解析：`WorkspaceManager` 已触及文件尺寸棘轮（只许缩减），
 * 这两个能力以扩展函数放在独立文件里，行为与其它委托方法一致。
 */

/** 只读文件头判断是否二进制，供编辑器/预览拦截 APK、图片、压缩包等产物。 */
suspend fun WorkspaceManager.inspectFile(projectName: String, relativePath: String): AppResult<WorkspaceFileInspection> =
    fileService.inspectFile(projectName, relativePath)

/** 工作区文件对应的宿主路径（FileProvider 外发、系统应用打开）。 */
suspend fun WorkspaceManager.hostFile(projectName: String, relativePath: String): AppResult<File> =
    fileService.hostFile(projectName, relativePath)