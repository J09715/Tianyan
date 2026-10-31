package top.tianyan.app.runtime.virtualdisplay

/** SF id 可能超过 Long.MAX_VALUE，保持十进制字符串；名称不唯一时拒绝猜屏。 */
internal fun physicalDisplayIdForName(dump: String, displayName: String): String? {
    if (!displayName.startsWith("ShowerVirtualDisplay-")) return null
    val line = Regex("""^\s*Display\s+(\d+)\b.*\bdisplayName="([^"]+)".*$""")
    return dump.lineSequence().mapNotNull { line.matchEntire(it) }
        .filter { it.groupValues[2] == displayName }
        .map { it.groupValues[1] }.toList().singleOrNull()
}

/** 私有虚拟屏可能对 App 的 DisplayManager 不可见，shell 的 DisplayInfo 仍包含逻辑 id。 */
internal fun logicalDisplayName(dump: String, logicalId: Int): String? =
    Regex("""DisplayInfo\{"([^"]+)",([^\r\n}]*)}""").findAll(dump)
        .filter { Regex("""\bdisplayId\s+(\d+)\b""").find(it.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() == logicalId }
        .map { it.groupValues[1] }.distinct().toList().singleOrNull()
