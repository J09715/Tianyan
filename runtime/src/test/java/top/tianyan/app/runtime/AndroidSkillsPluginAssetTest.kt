package top.tianyan.app.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.database.AgentSkillRepository

/**
 * `android-skills` 本地插件（Google Android CLI + 33 个官方 Android Skills）的资产守门。
 *
 * 插件资源是离线装配契约的一部分：清单字段、POSIX 脚本、结构化进度、离线纯度、
 * SHA-256 闭环与技能语料缺一不可，否则真机上会以“装完却不可用”的形式失败。
 */
class AndroidSkillsPluginAssetTest {

    private val pluginDir = File("../assets/plugins/android-skills")
    private val payloadDir = File(pluginDir, "payload")
    private val scriptsDir = File(payloadDir, "scripts")
    private val skillsDir = File(payloadDir, "skills")

    private val installer = File(scriptsDir, "install-android-skills.sh").readText()
    private val uninstaller = File(scriptsDir, "uninstall-android-skills.sh").readText()
    private val verifier = File(scriptsDir, "verify-android-skills.sh").readText()
    private val wrapper = File(scriptsDir, "android-wrapper.sh").readText()
    private val manifest = File(pluginDir, "manifest.json").readText()

    @Test
    fun manifestDeclaresOfflineArm64ProcessPluginWithSingleCommandEntry() {
        assertTrue(manifest.contains("\"schemaVersion\": 1"))
        assertTrue(manifest.contains("\"id\": \"android-skills\""))
        assertTrue(manifest.contains("\"source\": \"LOCAL\""))
        assertTrue(manifest.contains("\"offlineOnly\": true"))
        assertTrue(manifest.contains("\"installMethod\": \"LOCAL_PACKAGE\""))
        assertTrue(manifest.contains("\"architectures\": [\n    \"ARM64\"\n  ]"))
        assertTrue(manifest.contains("\"commandLinks\": [\n    \"android\"\n  ]"))
        // JSON 文本里引号是转义形态（\"），断言按原文匹配。
        val escapedQuote = "\\\""
        listOf(
            "install-android-skills.sh",
            "uninstall-android-skills.sh",
            "verify-android-skills.sh",
        ).forEach { recipe ->
            assertTrue(
                "manifest 未接线 $recipe",
                manifest.contains("/bin/sh ${escapedQuote}\$TIANYAN_PLUGIN_PAYLOAD/scripts/$recipe$escapedQuote"),
            )
        }
        // 安装脚本与技能目录都会写入沙箱全局路径，权限声明必须如实反映需要联网的子命令能力。
        assertTrue(manifest.contains("\"NETWORK\""))
    }

    @Test
    fun shellRecipesArePosixWithLfEndings() {
        listOf(
            "android-wrapper.sh" to wrapper,
            "install-android-skills.sh" to installer,
            "uninstall-android-skills.sh" to uninstaller,
            "verify-android-skills.sh" to verifier,
        ).forEach { (name, content) ->
            assertTrue("$name must start with /bin/sh shebang", content.startsWith("#!/bin/sh\n"))
            assertFalse("$name must not contain CRLF", content.contains('\r'))
        }
    }

    @Test
    fun installerIsOfflineOnlyAndPinsPluginOwnedUserHome() {
        listOf(installer, uninstaller, verifier).forEach { script ->
            assertFalse("offline 插件脚本不得联网", script.contains("curl"))
            assertFalse(script.contains("wget"))
            assertFalse(script.contains("apt-get"))
            assertFalse(script.contains("git clone"))
            assertFalse(script.contains("https://"))
        }
        // 引导器与 android-cli 必须双件内置：只装引导器会在首次运行时联网下载 85 MiB。
        assertTrue(installer.contains("payload/bin/android-launcher") || installer.contains("\$PAYLOAD/bin/android-launcher"))
        assertTrue(installer.contains("android-cli"))
        assertTrue(installer.contains("ANDROID_USER_HOME_DIR"))
        assertTrue(installer.contains("(cd \"\$PAYLOAD\" && sha256sum -c checksums/SHA256SUMS"))
    }

    @Test
    fun installerUnpacksFirstRunBeforeVerifyAndGuardsSkillScanRoot() {
        // 首次解包约 70 MiB，必须留在 15 分钟超时的安装步骤内，而不是 60 秒超时的 verifyCommand。
        assertTrue(installer.contains("cli/bundles"))
        assertTrue(installer.indexOf("cli/bundles") < installer.indexOf("verify-android-skills.sh"))
        // 技能必须落到自动扫描根；/attachments 未挂载时要显式失败，而不是写进 rootfs。
        assertTrue(installer.contains("test -d /attachments"))
        assertTrue(installer.contains("/attachments/skills/android-skills"))
        assertTrue(installer.contains("MIN_SKILL_COUNT=33"))
        val skillCount = installer.indexOf("SKILL.md' | wc -l")
        assertTrue(skillCount > 0)
        // 原子替换旧技能集合，避免“先删后写”留下空窗。
        assertTrue(installer.contains("android-skills.previous"))
    }

    @Test
    fun installerReportsMonotonicStructuredProgress() {
        val percentages = Regex("""progress (\d{1,3}) """)
            .findAll(installer)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertTrue(percentages.size >= 7)
        assertTrue(percentages.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(percentages.first() > 0)
        assertEquals(100, percentages.last())
        assertTrue(installer.contains("[TIANYAN_PROGRESS:%s]"))
        assertTrue(installer.contains("[COPY]"))
        assertTrue(installer.contains("[INSTALL]"))
        assertTrue(installer.contains("[VERIFY]"))
    }

    @Test
    fun verifierAssertsAarch64ElfBeforeRunningCli() {
        assertTrue(verifier.contains("b700"))
        assertTrue(verifier.contains("7f454c46"))
        assertTrue(verifier.split("require_aarch64").size >= 3)
        val firstElfAssertion = verifier.indexOf("require_aarch64")
        val firstExecution = verifier.indexOf("\" info")
        assertTrue(firstElfAssertion in 1 until firstExecution)
        assertTrue(verifier.contains("MIN_SKILL_COUNT=33"))
        // 命令入口是本插件产物，指向别处即视为被抢占。
        assertTrue(verifier.contains("/opt/tianyan/bin/android"))
    }

    @Test
    fun wrapperDefaultsToNoMetricsAndOwnUserHomeWithoutSelfExec() {
        assertTrue(wrapper.contains("--no-metrics"))
        assertTrue(wrapper.contains("ANDROID_USER_HOME"))
        assertTrue(wrapper.contains("android-user-home"))
        assertTrue(wrapper.contains("lib/android-launcher"))
        // exec 目标必须是引导器而不是包装层自身（PRoot 下自指 exec 会零输出空转）。
        assertFalse(wrapper.contains("exec \"\$0\""))
        assertFalse(wrapper.contains("exec \"\$TOOL_DIR/bin/android\""))
    }

    @Test
    fun vendoredSkillsKeepUpstreamFrontmatterReadable() {
        val skillDirs = skillsDir.listFiles().orEmpty().filter { it.isDirectory }
        assertEquals(33, skillDirs.size)

        skillDirs.forEach { dir ->
            val prompt = File(dir, "SKILL.md")
            assertTrue("missing SKILL.md in ${dir.name}", prompt.isFile)
            val meta = AgentSkillRepository.parseFrontmatter(prompt.readText())
            assertTrue("${dir.name} 缺少 name", !meta["name"].isNullOrBlank())
            assertTrue("${dir.name} 缺少 description", !meta["description"].isNullOrBlank())
        }

        // 官方 skills 的 description 是「普通多行标量」，解析器只取首行会把描述截断。
        val cliDescription = AgentSkillRepository.parseFrontmatter(
            File(skillsDir, "android-cli/SKILL.md").readText(),
        ).getValue("description")
        assertTrue(cliDescription.length > 200)
        assertTrue(cliDescription.contains("manage and interact with Android virtual devices"))
        assertTrue(cliDescription.endsWith("discover and install official Android skills."))
    }

    @Test
    fun checksumsCoverEveryVersionedPayloadFile() {
        val checksums = File(payloadDir, "checksums/SHA256SUMS").readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.split("  ", limit = 2).let { it[1] to it[0] } }
            .toMap()

        val payloadFiles = payloadDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(payloadDir).invariantSeparatorsPath }
            .filter { it != "checksums/SHA256SUMS" }
            .toList()

        assertTrue(payloadFiles.size > 400)
        payloadFiles.forEach { relative ->
            assertTrue("未被 SHA256SUMS 覆盖: $relative", checksums.containsKey(relative))
        }
        // bin/ 是本地获取的上游二进制（gitignore），缺失时允许只有清单条目；
        // 存在时必须与清单哈希一致。
        File(payloadDir, "bin").listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(payloadDir).invariantSeparatorsPath
            assertEquals(relative, checksums.getValue(relative), sha256(file))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
