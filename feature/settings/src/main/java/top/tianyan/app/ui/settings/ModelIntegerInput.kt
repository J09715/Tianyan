package top.tianyan.app.ui.settings

/** Blank uses the model default; nonblank values must fit the persisted Int. */
internal fun isValidOptionalModelInteger(text: String, minimum: Int): Boolean {
    val trimmed = text.trim()
    return trimmed.isEmpty() || trimmed.toIntOrNull()?.let { it >= minimum } == true
}
