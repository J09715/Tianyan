package top.tianyan.app.harness

/**
 * 把「微信」或包名解析成可启动的包名。
 * 应用名有多个相近结果时不猜测，带点的字符串则原样当作包名交给启动器。
 */
internal fun resolveLaunchablePackage(query: String, apps: List<Pair<String, String>>): String? {
    val name = query.trim()
    if (name.isEmpty()) return null
    apps.firstOrNull { (packageName, _) -> packageName.equals(name, ignoreCase = true) }?.let { return it.first }
    apps.firstOrNull { (_, label) -> label.equals(name, ignoreCase = true) }?.let { return it.first }
    val folded = name.lowercase()
    val hits = apps.filter { (_, label) ->
        val text = label.lowercase()
        text.contains(folded) || folded.contains(text)
    }
    if (hits.size == 1) return hits.first().first
    return name.takeIf { it.contains('.') }
}
