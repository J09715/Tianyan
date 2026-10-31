package top.tianyan.app.ui.settings

import top.tianyan.app.harness.model.ModelMetadataRegistry

/** 档案共享视觉开关：只有全部模型明确不支持图片时才自动关闭，未知模型沿用默认值。 */
internal fun profileVisionInput(modelIds: Collection<String>): Boolean {
    val models = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return models.isEmpty() || models.any {
        val capability = ModelMetadataRegistry.capabilityOf(it)
        !capability.known || capability.visionInput
    }
}
