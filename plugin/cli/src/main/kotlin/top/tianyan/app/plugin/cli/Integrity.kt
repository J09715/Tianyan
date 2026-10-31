package top.tianyan.app.plugin.cli

internal fun compareIntegrity(expected: Map<String, String>, actual: Map<String, String>): List<String> {
    val issues = mutableListOf<String>()
    (expected.keys - actual.keys).sorted().forEach { issues += "缺少 payload 文件：$it" }
    (actual.keys - expected.keys).sorted().forEach { issues += "清单未声明 payload 文件：$it" }
    (expected.keys intersect actual.keys).sorted().forEach { path ->
        if (expected.getValue(path) != actual.getValue(path)) issues += "SHA-256 不一致：$path"
    }
    return issues
}
