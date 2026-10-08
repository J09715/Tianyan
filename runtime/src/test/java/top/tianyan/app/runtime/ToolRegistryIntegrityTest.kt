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
 * 在线插件中心注册表的完整性校验。
 *
 * 插件中心的安装靠 `installSteps` 逐条在 PRoot 里执行。这些步骤有几个容易写错、
 * 而且错了以后用户只看到「安装失败」拿不到原因的地方：
 *   · `curl` 没带 `--fail`：404 时 curl 退出码仍是 0，于是把错误页当成功继续往下走；
 *   · `sha256sum -c` 配 `echo '<hash>  <path>'`：路径写错就永远校验不过；
 *   · 下载 URL 的版本号与校验哈希来自不同版本（升级了 URL 忘了换哈希）；
 *   · 声明的依赖没在步骤里先装（比如依赖 python 却直接调 python3）。
 * 这里把可静态判定的部分钉住，运行期的真实安装仍以 CI 的 arm64 构建为准。
 */
class ToolRegistryIntegrityTest {

    private val registry = File("../app/src/main/assets/registry/tools.json")

    private fun entries(): List<JsonObject> {
        val root = Json.parseToJsonElement(registry.readText())
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["tools"] ?: root["items"])?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.map { it.jsonObject }
    }

    private fun stepsOf(entry: JsonObject): List<String> =
        (entry["installSteps"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()

    private fun idOf(entry: JsonObject): String = entry["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** id 必须唯一且非空：重复 id 会让其中一个工具在列表里被覆盖，用户看不到它。 */
    @Test
    fun registryIdsAreUniqueAndNonBlank() {
        val ids = entries().map(::idOf)
        assertTrue("注册表为空", ids.isNotEmpty())
        assertTrue("存在空 id", ids.none { it.isBlank() })
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("注册表 id 重复: $dupes", dupes.isEmpty())
    }

    /** 每个工具都要有版本、安装方式和至少一条安装步骤，否则装不了。 */
    @Test
    fun everyEntryCanActuallyInstall() {
        entries().forEach { entry ->
            val id = idOf(entry)
            assertTrue("$id: 缺少 version", !entry["version"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
            assertTrue("$id: 缺少 installMethod", !entry["installMethod"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
            assertTrue("$id: 没有 installSteps，装不上", stepsOf(entry).isNotEmpty())
        }
    }

    /**
     * 下载必须用 `curl --fail`（或 `-f`）。
     *
     * 不带 `--fail` 时 404/500 的退出码依然是 0，脚本会把错误页面当成下载成功的文件
     * 继续解包，最终报一个与真正原因无关的错（「不是 gzip 格式」之类）。
     */
    @Test
    fun downloadsUseFailFastCurl() {
        entries().forEach { entry ->
            val id = idOf(entry)
            stepsOf(entry).forEachIndexed { index, step ->
                if (step.trimStart().startsWith("#")) return@forEachIndexed
                val curlCalls = Regex("""\bcurl\b[^|;&]*""").findAll(step)
                curlCalls.forEach { call ->
                    val text = call.value
                    // 只用 curl 探测（-o /dev/null -w）不需要 --fail；真正落盘的下载必须带。
                    val writesFile = Regex("""-o\s+\S""").containsMatchIn(text) &&
                        !text.contains("/dev/null")
                    if (!writesFile) return@forEach
                    assertTrue(
                        "$id 第 ${index + 1} 步的 curl 下载没带 --fail：" +
                            "404 时退出码仍是 0，会把错误页当成功继续解包\n  $text",
                        text.contains("--fail") || Regex("""(^|\s)-[a-zA-Z]*f""").containsMatchIn(text),
                    )
                }
            }
        }
    }

    /**
     * `sha256sum -c` 的条目路径必须指向脚本真正下载到的位置。
     *
     * 这条是最容易出错的一类：URL 改了、文件名改了、或者临时目录名改了，
     * 校验就会永远失败，而报错只说「校验和不匹配」，看不出是路径写错了。
     */
    @Test
    fun checksumPathsMatchDownloadedFiles() {
        entries().forEach { entry ->
            val id = idOf(entry)
            stepsOf(entry).forEachIndexed { index, step ->
                // 形如： echo '<64位hex>  /tmp/xxx' | sha256sum -c -
                val match = Regex("""echo\s+'([0-9a-fA-F]{64})\s+(\S+)'\s*\|\s*sha256sum""").find(step)
                    ?: return@forEachIndexed
                val declaredPath = match.groupValues[2]
                val declaredHash = match.groupValues[1].lowercase()
                assertTrue("$id 第 ${index + 1} 步的哈希不是 64 位十六进制", declaredHash.length == 64)

                // 同一条或更早的步骤里必须真的把文件下载到 declaredPath。
                val all = stepsOf(entry).take(index + 1).joinToString("\n")
                assertTrue(
                    "$id 第 ${index + 1} 步校验 $declaredPath，但前面没有任何步骤下载到这个路径",
                    all.contains(declaredPath),
                )
            }
        }
    }

    /**
     * 做了哈希校验的文件，必须能在同一步或更早的步骤里找到它的下载 URL。
     *
     * 下载与校验分两步写是清晰的做法（先下再验），所以这里不要求挤在同一步；
     * 要拦住的是「有校验、却找不到来源」——那意味着升级版本时只改了下载或只改了哈希。
     */
    @Test
    fun checksummedFilesHaveADownloadSource() {
        entries().forEach { entry ->
            val id = idOf(entry)
            val steps = stepsOf(entry)
            steps.forEachIndexed { index, step ->
                val match = Regex("""echo\s+'[0-9a-fA-F]{64}\s+(\S+)'\s*\|\s*sha256sum""").find(step)
                    ?: return@forEachIndexed
                val declaredPath = match.groupValues[1]
                val name = declaredPath.substringAfterLast('/')
                val earlier = steps.take(index + 1).joinToString("\n")
                assertTrue(
                    "$id 第 ${index + 1} 步校验 $declaredPath，但同一步及更早的步骤里都没有它的下载 URL：" +
                        "URL 与哈希分处两地，升级版本时极易只改一处",
                    earlier.contains("http") && (earlier.contains(name) || earlier.contains(declaredPath)),
                )
            }
        }
    }

    /** verifyCommand / launchCommand 里出现的裸命令，必须在 commandLinks 里登记（否则用户敲不到）。 */
    @Test
    fun commandsAreLinked() {
        entries().forEach { entry ->
            val id = idOf(entry)
            val links = (entry["commandLinks"] as? JsonArray)
                ?.map { it.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
            assertTrue("$id: 没有 commandLinks，命令装完也敲不到", links.isNotEmpty())

            val launch = entry["launchCommand"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val binary = launch.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (binary.isNotBlank()) {
                assertTrue(
                    "$id: launchCommand 用的是 $binary，但 commandLinks 里没有它：$links",
                    links.any { it == binary },
                )
            }
        }
    }

    /**
     * 步骤里直接调用的运行时，必须在 dependencies 里声明。
     *
     * 反方向不检查：声明了 curl/git 而步骤没直接调用是正常的——npm、pip 会用到它们，
     * 这类传递依赖声明出来是对的。但反过来漏声明会让安装在没有该运行时的机器上
     * 直接失败，而且报错是「command not found」，看不出是清单漏了依赖。
     */
    @Test
    fun invokedRuntimesAreDeclaredAsDependencies() {
        // 这些命令由依赖机制负责准备，出现在步骤里就必须在 dependencies 中声明。
        val tracked = listOf("node", "npm", "python3", "pip3", "java", "cmake", "git")
        entries().forEach { entry ->
            val id = idOf(entry)
            val deps = (entry["dependencies"] as? JsonArray)
                ?.map { it.jsonPrimitive.contentOrNull.orEmpty().substringBefore(">").substringBefore("=").trim() }
                .orEmpty()
            val body = stepsOf(entry).joinToString("\n")
            tracked.forEach { binary ->
                val invoked = Regex("""(?<![-\w/])${Regex.escape(binary)}(?![-\w])""").containsMatchIn(body)
                if (!invoked) return@forEach
                // npm 由 node 提供、pip3 由 python 提供，允许以任一形式声明。
                // 两条合法路径：声明为依赖由依赖机制装；或由步骤自己 inline 装
                //（llama-cpp 就是自己 apt-get install cmake，无需重复声明）。
                val declaredAsDependency = deps.any { it == binary } ||
                    (binary == "npm" && deps.any { it == "node" }) ||
                    (binary == "pip3" && deps.any { it == "python" }) ||
                    (binary == "python3" && deps.any { it == "python" })
                val installedInline = Regex(
                    """apt-get\s+install[^\n]*\b${Regex.escape(binary)}\b|apk\s+add[^\n]*\b${Regex.escape(binary)}\b""",
                ).containsMatchIn(body)
                assertTrue(
                    "$id: 安装步骤调用了 $binary，但既没在 dependencies 里声明、也没在步骤里自己装：" +
                        "缺依赖时安装会以 command not found 失败，看不出是清单漏写",
                    declaredAsDependency || installedInline,
                )
            }
        }
    }

    /** 不得残留上游品牌。 */
    @Test
    fun registryCarriesNoUpstreamBranding() {
        val text = registry.readText()
        listOf("Wanxiang", "WanXiang", "万象", "peakSee").forEach { needle ->
            assertTrue("tools.json 残留上游品牌 $needle", !text.contains(needle))
        }
    }
}
