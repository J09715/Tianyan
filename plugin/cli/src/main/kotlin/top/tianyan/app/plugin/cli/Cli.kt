package top.tianyan.app.plugin.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private class UsageException(message: String) : Exception(message)

class Cli(private val out: PrintStream, private val err: PrintStream) {
    fun run(arguments: Array<String>): Int {
        if (arguments.isEmpty()) {
            usage()
            return 2
        }
        return try {
            when (arguments[0]) {
                "-h", "--help", "help" -> {
                    usage()
                    0
                }
                "check" -> check(arguments)
                "pack" -> pack(arguments)
                "inspect" -> inspect(arguments)
                else -> throw UsageException("未知命令：${arguments[0]}")
            }
        } catch (failure: UsageException) {
            err.println(failure.message)
            usage()
            2
        } catch (failure: Exception) {
            err.println("失败：${failure.message ?: failure::class.java.simpleName}")
            1
        }
    }

    private fun check(arguments: Array<String>): Int {
        requireProjectArgument(arguments, "check")
        val project = PluginProject.load(Path.of(arguments[1]))
        Summary.print(out, project.manifest, project.files.values)
        val issues = project.integrityIssues()
        if (issues.isEmpty()) {
            out.println("完整性：与 payload 一致")
            return 0
        }
        err.println("完整性检查失败：")
        issues.forEach(err::println)
        return 1
    }

    private fun pack(arguments: Array<String>): Int {
        if (arguments.size < 2) throw UsageException("用法：pack <工程目录> [--output <插件包>] [--write-manifest]")
        var output: Path? = null
        var writeManifest = false
        var index = 2
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "-o", "--output" -> {
                    if (index + 1 >= arguments.size) throw UsageException("缺少 --output 路径")
                    output = Path.of(arguments[++index])
                }
                "--write-manifest" -> writeManifest = true
                else -> throw UsageException("未知选项：$option")
            }
            index++
        }
        val project = PluginProject.load(Path.of(arguments[1]))
        val issues = project.integrityIssues()
        if (issues.isNotEmpty()) {
            out.println("提示：清单完整性已按 payload 重新计算（${issues.size} 处差异）")
        }
        val target = output ?: project.root.resolve("dist")
            .resolve("${project.manifest.id}-${project.manifest.version}.txplugin")
        val archive = ArchiveWriter.pack(project, target, writeManifest)
        out.println("已生成：$archive")
        out.println("大小：${formatBytes(Files.size(archive))}")
        if (writeManifest) out.println("已更新：${project.root.resolve("manifest.json")}")
        return 0
    }

    private fun inspect(arguments: Array<String>): Int {
        if (arguments.size != 2) throw UsageException("用法：inspect <插件包>")
        val inspection = ArchiveInspector.inspect(Path.of(arguments[1]))
        Summary.print(out, inspection.manifest, inspection.files.values)
        out.println("完整性：与 payload 一致")
        return 0
    }

    private fun requireProjectArgument(arguments: Array<String>, command: String) {
        if (arguments.size != 2) throw UsageException("用法：$command <工程目录>")
    }

    private fun usage() {
        out.println(
            """
            天衍插件作者工具
            用法：
              tianyan-plugin check <工程目录>
              tianyan-plugin pack <工程目录> [--output <插件包>] [--write-manifest]
              tianyan-plugin inspect <插件包>
            """.trimIndent(),
        )
    }
}
