package top.tianyan.app.plugin.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol

class PluginCliTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var output: ByteArrayOutputStream
    private lateinit var errors: ByteArrayOutputStream
    private lateinit var cli: Cli

    @Before
    fun setUp() {
        output = ByteArrayOutputStream()
        errors = ByteArrayOutputStream()
        cli = Cli(PrintStream(output, true, Charsets.UTF_8), PrintStream(errors, true, Charsets.UTF_8))
    }

    @After
    fun tearDown() {
        output.close()
        errors.close()
    }

    @Test
    fun `check passes when manifest matches payload`() {
        val files = mapOf("bin/run.sh" to "#!/bin/sh\necho hello\n")
        val root = project(files, manifest(integrityOf(files)))

        assertEquals(0, cli.run(arrayOf("check", root.toString())))
        assertTrue(output.toString().contains("完整性：与 payload 一致"))
    }

    @Test
    fun `check reports missing extra and changed files`() {
        val root = project(mapOf("a.txt" to "changed"), manifest(mapOf(
            "a.txt" to sha256("old"),
            "missing.txt" to sha256("x"),
        )))
        Files.writeString(root.resolve("payload/extra.txt"), "extra")

        assertEquals(1, cli.run(arrayOf("check", root.toString())))
        val text = errors.toString()
        assertTrue(text.contains("SHA-256 不一致：a.txt"))
        assertTrue(text.contains("缺少 payload 文件：missing.txt"))
        assertTrue(text.contains("清单未声明 payload 文件：extra.txt"))
    }

    @Test
    fun `pack writes computed integrity and inspect accepts the archive`() {
        val files = mapOf("bin/run.sh" to "#!/bin/sh\necho hello\n", "config/app.conf" to "name=demo\n")
        val root = project(files, manifest(emptyMap()))
        val archive = temporary.root.toPath().resolve("sample.txplugin")

        assertEquals(0, cli.run(arrayOf("pack", root.toString(), "--output", archive.toString(), "--write-manifest")))
        assertEquals(integrityOf(files), embeddedManifest(archive).plugin?.integrity)
        assertEquals(0, cli.run(arrayOf("inspect", archive.toString())))
        assertTrue(output.toString().contains("完整性：与 payload 一致"))
        assertEquals(0, cli.run(arrayOf("check", root.toString())))
    }

    @Test
    fun `pack is deterministic for the same project`() {
        val files = mapOf("bin/run.sh" to "#!/bin/sh\necho hello\n")
        val root = project(files, manifest(integrityOf(files)))
        val first = temporary.root.toPath().resolve("first.txplugin")
        val second = temporary.root.toPath().resolve("second.txplugin")

        assertEquals(0, cli.run(arrayOf("pack", root.toString(), "--output", first.toString())))
        assertEquals(0, cli.run(arrayOf("pack", root.toString(), "--output", second.toString())))
        org.junit.Assert.assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second))
    }

    @Test
    fun `inspect rejects root entries outside manifest and payload`() {
        val archive = zipOf("manifest.json" to manifest(emptyMap()), "other.txt" to "x")

        assertEquals(1, cli.run(arrayOf("inspect", archive.toString())))
        assertTrue(errors.toString().contains("只允许 manifest.json 与 payload/"))
    }

    @Test
    fun `inspect rejects path traversal entries`() {
        val traversal = zipOf("manifest.json" to manifest(emptyMap()), "../evil" to "x")
        assertEquals(1, cli.run(arrayOf("inspect", traversal.toString())))
        assertTrue(errors.toString().contains("不安全路径"))
    }

    @Test
    fun `check rejects unknown v2 fields and v1 manifests`() {
        val unknown = project(emptyMap(), manifest(emptyMap(), extraRoot = ""","unknown":true"""))
        assertEquals(1, cli.run(arrayOf("check", unknown.toString())))
        assertTrue(errors.toString().contains("清单校验失败"))

        val legacy = project(emptyMap(), """{"schemaVersion":1,"id":"sample-plugin","name":"Sample","description":"Sample"}""")
        assertEquals(1, cli.run(arrayOf("check", legacy.toString())))
        assertTrue(errors.toString().contains("只支持 v2"))
    }

    @Test
    fun `pack refuses output inside payload`() {
        val root = project(mapOf("a.txt" to "a"), manifest(emptyMap()))
        val target = root.resolve("payload/out.txplugin")

        assertEquals(1, cli.run(arrayOf("pack", root.toString(), "--output", target.toString())))
        assertTrue(errors.toString().contains("输出文件不能放在 payload"))
    }

    private fun project(files: Map<String, String>, manifestText: String): Path {
        val root = temporary.newFolder().toPath()
        Files.writeString(root.resolve("manifest.json"), manifestText)
        val payload = Files.createDirectories(root.resolve("payload"))
        files.forEach { (name, content) ->
            val target = payload.resolve(name)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return root
    }

    private fun manifest(integrity: Map<String, String>, extraRoot: String = ""): String =
        """
        {
          "schemaVersion": 2,
          "id": "sample-plugin",
          "name": "Sample",
          "description": "Sample plugin",
          "version": "1.0.0",
          "source": "LOCAL",
          "offlineOnly": true,
          "installMethod": "LOCAL_PACKAGE",
          "installSteps": [],
          "plugin": {
            "id": "example.sample",
            "api": {"minimum": 1, "maximum": 1},
            "execution": "PROCESS",
            "extensions": [],
            "dependencies": [],
            "capabilities": [],
            "resources": [],
            "integrity": ${jsonMap(integrity)}
          }$extraRoot
        }
        """.trimIndent()

    private fun integrityOf(files: Map<String, String>): Map<String, String> = files.mapValues { sha256(it.value) }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun jsonMap(values: Map<String, String>): String =
        values.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }

    private fun zipOf(vararg entries: Pair<String, String>): Path {
        val archive = temporary.root.toPath().resolve("manual-${System.nanoTime()}.txplugin")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun embeddedManifest(archive: Path): ToolManifest = ZipFile(archive.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry("manifest.json"))
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        PluginPackageProtocol.decode(text)
    }
}
