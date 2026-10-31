package top.tianyan.app.core.model

data class ChatBackgroundStyle(
    val imageName: String? = null,
    val visibility: Float = 0.65f,
    val blurDp: Float = 0f,
) {
    fun normalized() = copy(
        imageName = imageName?.takeIf(::isValidImageName),
        visibility = if (visibility.isFinite()) visibility.coerceIn(0f, 1f) else 0.65f,
        blurDp = if (blurDp.isFinite()) blurDp.coerceIn(0f, 24f) else 0f,
    )

    companion object {
        const val IMAGE_DIRECTORY = "chat-backgrounds"
        private val imagePattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.img")
        fun isValidImageName(name: String) = imagePattern.matches(name)
    }
}
