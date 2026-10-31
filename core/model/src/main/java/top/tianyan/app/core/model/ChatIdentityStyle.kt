package top.tianyan.app.core.model

data class ChatIdentityStyle(val name: String = DEFAULT_NAME, val imageName: String? = null) {
    fun normalized(): ChatIdentityStyle {
        val clean = name.replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim().ifBlank { DEFAULT_NAME }
        val end = clean.offsetByCodePoints(0, minOf(MAX_NAME_LENGTH, clean.codePointCount(0, clean.length)))
        return copy(name = clean.substring(0, end), imageName = imageName?.takeIf(ChatBackgroundStyle::isValidImageName))
    }
    companion object {
        const val DEFAULT_NAME = "天衍"
        const val MAX_NAME_LENGTH = 40
        const val IMAGE_DIRECTORY = "chat-identity"
    }
}
