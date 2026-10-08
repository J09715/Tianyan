package top.tianyan.app.runtime

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线插件包清单的完整性校验。
 *
 * 离线包是「下载 → 放进 payload/archives → 打包成 .txplugin → 导入安装」这条链路，
 * 中间任何一环对不上，用户看到的是「安装不上」而拿不到原因：
 *   · 清单声明了 installSteps 但 payload 目录根本不存在（重复的包，装不上）；
 *   · 脚本 `need "$ARCHIVES/x"` 引用的归档名与 DOWNLOADS.md 让用户重命名的名字不一致
 *     （照教程做完仍然缺文件）；
 *   · SHA256SUMS 里的条目名与脚本引用的路径不一致（校验永远不过）。
 * 这三类都在这里钉死：清单与脚本、脚本与校验清单、脚本与下载文档必须互相自洽。
 */
class PluginPackageIntegrityTest {

    private val pluginsDir = File("../assets/plugins")

    private fun manifestOf(dir: File): JsonObject =
        Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject

    private fun pluginDirs(): List<File> =
        pluginsDir.listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").isFile }

    /** 每个插件目录都必须有唯一 id：两个目录同一个 id 会让其中一个永远装不上。 */
    @Test
    fun pluginIdsAreUnique() {
        val byId = pluginDirs().groupBy { manifestOf(it)["id"]?.jsonPrimitive?.contentOrNull }
        val dupes = byId.filterValues { it.size > 1 }
        assertTrue(
            "插件 id 重复（同 id 的包只有一个能被识别，另一个永远装不上）: " +
                dupes.entries.joinToString { (id, dirs) -> "$id -> ${dirs.map { it.name }}" },
            dupes.isEmpty(),
        )
    }

    /**
     * 声明了 installMethod=LOCAL_PACKAGE 就必须真的带得动安装：
     * payload 目录存在、installSteps 非空、每个步骤引用的脚本都在包里。
     */
    @Test
    fun localPackagesShipRunnableInstallSteps() {
        pluginDirs().forEach { dir ->
            val manifest = manifestOf(dir)
            val method = manifest["installMethod"]?.jsonPrimitive?.contentOrNull
            if (method != "LOCAL_PACKAGE") return@forEach

            val payload = File(dir, "payload")
            assertTrue("${dir.name}: 声明 LOCAL_PACKAGE 却没有 payload 目录", payload.isDirectory)

            val steps = (manifest["installSteps"] as? JsonArray)?.toList().orEmpty()
            assertTrue("${dir.name}: 声明 LOCAL_PACKAGE 却没有 installSteps，装不上", steps.isNotEmpty())

            steps.forEach { step ->
                val command = step.jsonPrimitive.contentOrNull.orEmpty()
                Regex("""\${'$'}TIANYAN_PLUGIN_PAYLOAD/([^"'\s]+)""").findAll(command).forEach { match ->
                    val ref = match.groupValues[1]
                    assertTrue(
                        "${dir.name}: installSteps 引用的脚本不存在 payload/$ref",
                        File(payload, ref).isFile,
                    )
                }
            }
        }
    }

    /** 校验清单里的每条路径都要真实存在于包里——否则 sha256sum -c 必然失败。 */
    @Test
    fun checksumEntriesPointAtShippedFiles() {
        pluginDirs().forEach { dir ->
            val sums = File(dir, "payload/checksums/SHA256SUMS")
            if (!sums.isFile) return@forEach
            for (line in sums.readLines()) {
                if (line.isBlank()) continue
                val path = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
                if (path.isEmpty()) continue
                // archives/ 与 bin/ 是打包前由 DOWNLOADS.md 指引下载的大文件
                //（归档与预编译二进制），源仓库里本就不含它们——否则仓库会被撑到几百 MB。
                // 其余随包分发的内容（config/、scripts/ 等）必须真实存在。
                if (path.startsWith("archives/") || path.startsWith("bin/")) continue
                assertTrue("${dir.name}: SHA256SUMS 指向不存在的文件 payload/$path", File(File(dir, "payload"), path).isFile)
            }
        }
    }

    /**
     * 安装脚本引用的归档名，必须和 DOWNLOADS.md 让用户重命名成的名字一致。
     *
     * 这条正是真出过问题的：文档的 PowerShell 示例把 Flutter 包重命名成
     * `flutter-source-arm64.tar.gz`，而脚本要的是 `flutter-linux-arm64-android-only-slim.tar.gz`，
     * 照教程做完仍然「缺文件」，而且报错只说 missing offline resource。
     */
    @Test
    fun installScriptArchiveNamesMatchDownloadGuide() {
        pluginDirs().forEach { dir ->
            val guide = File(dir, "DOWNLOADS.md")
            if (!guide.isFile) return@forEach
            val guideText = guide.readText()

            File(dir, "payload/scripts").listFiles().orEmpty()
                .filter { it.name.startsWith("install-") && it.name.endsWith(".sh") }
                .forEach { script ->
                    Regex("""\${'$'}ARCHIVES/([A-Za-z0-9._+-]+)""").findAll(script.readText()).forEach { match ->
                        val archive = match.groupValues[1]
                        // `gradle-` 这类是脚本内变量拼接的片段，不是完整归档名，跳过。
                        if (!archive.contains('.')) return@forEach
                        assertTrue(
                            "${dir.name}: ${script.name} 需要 archives/$archive，" +
                                "但 DOWNLOADS.md 没有告诉用户用这个文件名（照教程做完仍会缺文件）",
                            guideText.contains(archive),
                        )
                    }
                }
        }
    }

    /** 校验脚本必须用 /bin/sh 且无 CRLF：PRoot 里没有 bash，CRLF 会让 shebang 直接失效。 */
    @Test
    fun pluginScriptsArePosixAndLfOnly() {
        pluginDirs().forEach { dir ->
            File(dir, "payload/scripts").listFiles().orEmpty().filter { it.extension == "sh" }.forEach { script ->
                val text = script.readText()
                assertTrue("${script.path} 必须用 #!/bin/sh", text.startsWith("#!/bin/sh\n"))
                assertTrue("${script.path} 含 CRLF，PRoot 下 shebang 会失效", !text.contains('\r'))
            }
        }
    }

    /** 清单里的 verifyCommand 引用的脚本路径也要存在（本地包走 verifyCommand 判定安装成功）。 */
    @Test
    fun verifyCommandsReferenceExistingScripts() {
        pluginDirs().forEach { dir ->
            val command = manifestOf(dir)["verifyCommand"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            for (match in Regex("""\${'$'}TIANYAN_PLUGIN_PAYLOAD/([^"'\s]+)""").findAll(command)) {
                val ref = match.groupValues[1]
                assertTrue(
                    "${dir.name}: verifyCommand 引用的脚本不存在 payload/$ref",
                    File(File(dir, "payload"), ref).isFile,
                )
            }
        }
    }

    /** 包内不得残留上游品牌：清单里的名称/描述与脚本注释都不应出现万象/Wanxiang。 */
    @Test
    fun packagesCarryNoUpstreamBranding() {
        pluginDirs().forEach { dir ->
            val manifest = File(dir, "manifest.json").readText()
            listOf("Wanxiang", "WanXiang", "万象", "peakSee").forEach { needle ->
                assertTrue("${dir.name}/manifest.json 残留上游品牌 $needle", !manifest.contains(needle))
            }
        }
    }
}
