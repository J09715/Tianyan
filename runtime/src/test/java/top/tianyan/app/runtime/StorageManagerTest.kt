package top.tianyan.app.runtime

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.runtime.rootfs.RootfsValidator

class StorageManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: TestContext
    private lateinit var paths: RuntimePathManager
    private lateinit var manager: StorageManager
    private var blocked = false
    private var cancelled = false

    @Before fun setup() {
        context = TestContext(folder.newFolder("app"))
        paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
        val unused = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
                _, method, _ -> error("Unexpected method: ${method.name}")
        } as LinuxRuntime
        val runtime = object : LinuxRuntime by unused {
            override suspend fun withStorageCleanup(block: suspend () -> Unit) {
                if (cancelled) throw CancellationException("cancelled")
                check(!blocked) { "请先停止任务" }
                block()
            }
        }
        manager = StorageManager(context, paths, lazy { runtime })
        write(File(paths.rootfsDir("debian"), "usr/bin/bash"), 10)
    }
    private fun write(file: File, size: Int = 16, old: Boolean = true): File {
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(size) { 42 })
        if (old) assertTrue(file.setLastModified(System.currentTimeMillis() - 9L * 24 * 60 * 60 * 1000))
        return file
    }

    @Test fun mountedHomeAndAllFilesAreCountedExactlyOnce() = runBlocking {
        write(File(paths.homeDir("debian"), ".gradle/caches/modules-2/files-2.1/lib.jar"), 30)
        write(File(paths.homeDir("debian"), ".ssh/key"), 11)
        write(File(paths.tianyanRuntimesDir("debian"), "flutter/bin/dart"), 40)
        write(File(paths.tianyanRootDir("debian"), "toolchains/android/sdk/other.dat"), 13)
        write(File(paths.baseDir, "metadata/unknown"), 7)
        write(File(paths.workspaceDir, "export.apk"), 9)
        write(context.getDatabasePath("tianyan.db"), 12)
        write(File(context.filesDir, "other/data"), 6)
        write(File(context.filesDir.parentFile, "no_backup/config"), 5)
        write(File(context.filesDir.parentFile, "app_webview/data"), 8)
        val usage = manager.inspect()
        assertEquals(151L, usage.totalManagedBytes)
        assertEquals(6, usage.categories.size)
        val gradle = usage.categories.single { it.id == "cache" }.entries.single()
        assertEquals(30L, gradle.reclaimableBytes)
        assertTrue(gradle.path!!.startsWith(paths.homeDir("debian").absolutePath))
        assertTrue(manager.clearEntry("cache", gradle.id).isSuccess)
        assertFalse(File(paths.homeDir("debian"), ".gradle/caches/modules-2/files-2.1/lib.jar").exists())
        assertTrue(File(paths.homeDir("debian"), ".ssh/key").exists())
    }
    @Test fun recommendedCleanupOnlyDeletesOldRotatedLogs() = runBlocking {
        val oldLog = write(File(paths.logsDir, "agent.log.1"), 17)
        val preserved = listOf(
            write(File(paths.logsDir, "agent.log")), write(File(paths.logsDir, "agent.log.2"), old = false),
            write(File(paths.rootfsPreviousDir("debian"), "etc/config")),
            write(File(paths.stagingRootfsDir("debian"), "unpacking")), write(File(paths.tmpDir, "proot.sock")),
            write(File(paths.tianyanRootDir("debian"), "imports/tool/payload")),
            write(File(paths.cacheDir, "rootfs.tar")), write(File(paths.homeDir("debian"), ".npm/_cacache/pkg")),
        )
        assertEquals(17L, manager.inspect().safeCleanableBytes)
        assertEquals(17L, manager.quickSafeClean().getOrNull())
        assertFalse(oldLog.exists())
        preserved.forEach { assertTrue(it.path, it.exists()) }
    }
    @Test fun cacheCategoryMatchesPlanAndPreservesLivePackageState() = runBlocking {
        val goDownload = write(File(paths.homeDir("debian"), "go/pkg/mod/cache/download/pkg.zip"), 23)
        val deb = write(File(paths.rootfsDir("debian"), "var/cache/apt/archives/pkg.deb"), 31)
        val preserved = listOf(
            write(File(paths.homeDir("debian"), "go/pkg/mod/example/pkg.go")),
            write(File(paths.homeDir("debian"), "go/pkg/mod/cache/download/pkg.lock")),
            write(File(paths.rootfsDir("debian"), "var/cache/apt/archives/partial/pkg.deb")),
            write(File(paths.logsDir, "agent.log.1")), write(File(paths.homeDir("debian"), ".pub-cache/bin/tool")),
            write(File(paths.rootfsPreviousDir("debian"), "data")),
            write(File(paths.homeDir("debian"), ".gradle/wrapper/dists/8/gradle/lib/core.jar")),
            write(File(paths.homeDir("debian"), ".cache/yarn/v6/pkg/package.json")),
        )
        val usage = manager.inspect()
        assertEquals(54L, usage.categories.single { it.id == "cache" }.reclaimableBytes)
        assertTrue(manager.clearCategory("cache").isSuccess)
        assertFalse(goDownload.exists()); assertFalse(deb.exists())
        preserved.forEach { assertTrue(it.path, it.exists()) }
    }
    @Test fun projectCleanupNeverGuessesGenericBuildDirectoryNames() = runBlocking {
        val project = File(paths.workspaceDir, "sample")
        val preserved = listOf(write(File(project, "build.gradle.kts")),
            write(File(project, "build/src/important.kt")), write(File(project, "dist/source.js")),
            write(File(project, "out/release.apk")), write(File(paths.workspaceDir, "unknown/.gradle/user.txt")))
        val cache = write(File(project, ".gradle/8/file.bin"), 33)
        val usage = manager.inspect()
        assertEquals(33L, usage.projectCleanableBytes)
        assertEquals(33L, usage.cautionCleanableBytes)
        assertEquals(33L, manager.cleanProjectBuildArtifacts("sample").getOrNull())
        assertFalse(cache.exists())
        preserved.forEach { assertTrue(it.path, it.exists()) }
    }
    @Test fun changedAndNewFilesSurviveAnOlderPlan() = runBlocking {
        val changed = write(File(paths.logsDir, "a.log.1"))
        val stable = write(File(paths.logsDir, "b.log.1"))
        manager.inspect()
        changed.appendText("new bytes")
        val added = write(File(paths.logsDir, "c.log.1"))
        val result = manager.quickSafeClean()
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull()!!.message.contains("跳过 1"))
        assertTrue(changed.exists()); assertTrue(added.exists()); assertFalse(stable.exists())
    }
    @Test fun unknownMismatchedAndReadOnlyIdsFail() = runBlocking {
        val log = write(File(paths.logsDir, "a.log.1"))
        val attachment = write(File(paths.attachmentsDir, "unique.txt"))
        val usage = manager.inspect()
        val logEntry = usage.categories.single { it.id == "app_data" }.entries.single { it.cleanable }
        assertTrue(manager.clearEntry("cache", logEntry.id).isFailure)
        assertTrue(manager.clearEntry("app_data", "../../outside").isFailure)
        assertTrue(manager.clearCategory("unknown").isFailure)
        assertTrue(manager.clearCategory("conversations").isFailure)
        assertTrue(log.exists()); assertTrue(attachment.exists())
    }
    @Test fun activeRuntimeRejectsCleanupAndCancellationPropagates() = runBlocking {
        val log = write(File(paths.logsDir, "a.log.1"))
        manager.inspect()
        blocked = true
        assertTrue(manager.quickSafeClean().isFailure)
        blocked = false; cancelled = true
        try { manager.quickSafeClean(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(log.exists())
    }
    @Test fun symlinkedAncestorsAreNotFollowed() = runBlocking {
        val outside = folder.newFolder("outside")
        val valuable = write(File(outside, "important.log.1"))
        paths.logsDir.mkdirs()
        try { Files.createSymbolicLink(File(paths.logsDir, "linked").toPath(), outside.toPath()) }
        catch (error: Exception) { assumeNoException(error) }
        assertEquals(0L, manager.inspect().safeCleanableBytes)
        manager.quickSafeClean()
        assertTrue(valuable.exists())
    }
    @Test fun directoryReplacedBySymlinkCannotEscapePlan() = runBlocking {
        val nested = File(paths.logsDir, "nested")
        val original = write(File(nested, "a.log.1"))
        manager.inspect()
        val outside = folder.newFolder("replacement")
        val valuable = write(File(outside, "a.log.1"))
        original.delete(); nested.delete()
        try { Files.createSymbolicLink(nested.toPath(), outside.toPath()) }
        catch (error: Exception) { assumeNoException(error) }
        assertTrue(manager.quickSafeClean().isFailure)
        assertTrue(valuable.exists())
    }
    @Test fun runtimeGateExcludesCleanupAndNewCommands() = runBlocking {
        val gate = StorageActivityGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val task = async { gate.activity { entered.complete(Unit); finish.await() } }
        entered.await()
        gate.activity { /* Commands remain concurrent. */ }
        try { gate.cleanup { fail("must not run") }; fail("must reject") } catch (_: IllegalStateException) { }
        finish.complete(Unit); task.await()
        gate.cleanup {
            try { gate.activity { fail("must not launch") }; fail("must reject") } catch (_: IllegalStateException) { }
        }
        gate.activity { }
    }
    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getCodeCacheDir(): File = File(root, "code_cache").apply { mkdirs() }
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply { nativeLibraryDir = File(root, "lib").path }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
    }
}
