package top.tianyan.app.ui.workspace

/** In-memory document state, retained by the editor's ViewModel. */
internal class EditorDraft {
    private var project: String? = null
    private var path: String? = null
    private var generation = 0L
    private var savedText = ""
    var text: String = ""
        private set
    val isDirty: Boolean get() = text != savedText

    /** 编辑器最后已知的磁盘内容：载入时或上次成功保存后的基线，供保存前的版本校验使用。 */
    val savedContent: String get() = savedText

    fun matches(project: String?, path: String?): Boolean =
        this.project != null && this.project == project && this.path == path

    fun load(project: String, path: String, content: String) {
        generation++
        this.project = project
        this.path = path
        savedText = content
        text = content
    }

    fun edit(content: String) { text = content }
    fun reset() { text = savedText }
    fun snapshot(): SaveSnapshot = SaveSnapshot(generation, text)

    /** A completed write acknowledges its snapshot without replacing newer edits. */
    fun markSaved(snapshot: SaveSnapshot): Boolean {
        if (generation != snapshot.generation) return false
        savedText = snapshot.text
        return true
    }

    fun clear() {
        generation++
        project = null
        path = null
        savedText = ""
        text = ""
    }

    data class SaveSnapshot(val generation: Long, val text: String)
}
