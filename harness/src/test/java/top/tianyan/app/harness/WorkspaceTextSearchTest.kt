package top.tianyan.app.harness

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.common.result.AppResult

class WorkspaceTextSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = temporary.newFolder()
    private fun File.put(path: String, content: String) = File(this, path).apply { parentFile.mkdirs(); writeText(content) }
    private fun AppResult<String>.text() = (this as AppResult.Success).data

    @Test fun literalSearchReportsRelativePathsAndLineNumbersWithoutShell() = runBlocking<Unit> {
        val root = root()
        root.put("src/a.kt", "first\n中文 a.* \$(touch injected)\nlast\n")
        val operations = WorkspaceFileAccess(root)
        val result = operations.search("/workspace/src", "a.*", 50).text()
        assertTrue(result.contains("a.kt:2: 中文 a.*"))
        assertFalse(result.contains(root.absolutePath))
        assertFalse(File(root, "injected").exists())
        assertTrue(operations.search("src/a.kt", "last", 50).text().contains("a.kt:3: last"))
    }

    @Test fun escapePathsAreRejectedAndSymlinkTreesAreNotFollowed() = runBlocking<Unit> {
        val root = root()
        val outside = root().apply { put("secret.txt", "needle") }
        val operations = WorkspaceFileAccess(root)
        assertTrue(operations.search("../secret.txt", "needle", 50) is AppResult.Failure)
        assertTrue(operations.search(outside.absolutePath, "needle", 50) is AppResult.Failure)
        // Creating links requires privilege on some Windows installations; boundary checks above always run.
        try { Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath()) }
        catch (_: Exception) { return@runBlocking }
        assertTrue(operations.search("escape", "needle", 50) is AppResult.Failure)
        val scan = operations.search(".", "needle", 50).text()
        assertFalse(scan.contains("secret.txt"))
        assertTrue(scan.contains("结果不完整"))
    }

    @Test fun excludedBinaryOversizedAndDepthLimitedEntriesAreExplicitlyBounded() = runBlocking<Unit> {
        val root = root()
        root.put("build/generated.txt", "needle")
        root.put("node_modules/pkg/index.js", "needle")
        root.put("binary", "needle\u0000secret")
        root.put("large", "x".repeat(1024 * 1024 + 1) + "needle")
        root.put((1..18).joinToString("/") { "d$it" } + "/deep.txt", "needle")
        val output = WorkspaceFileAccess(root).search(".", "needle", 50).text()
        assertTrue(output.contains("未发现匹配"))
        assertTrue(output.contains("结果不完整"))
        assertFalse(output.contains("generated.txt:"))
    }

    @Test fun resultLimitAndLongLinesRemainSmallAndMarkedIncomplete() = runBlocking<Unit> {
        val root = root()
        root.put("lines", (1..200).joinToString("\n") { "x".repeat(10000) + "needle" }.take(900000))
        val output = WorkspaceFileAccess(root).search(".", "needle", 2).text()
        assertTrue(output.contains("命中 2"))
        assertTrue(output.contains("结果不完整"))
        assertTrue(output.length < 2000)
        assertTrue(output.contains("needle"))
    }

    @Test fun aggregateReadBudgetAndElapsedBudgetCannotReportComplete() = runBlocking<Unit> {
        val root = root()
        repeat(10) { root.put("file$it", "x".repeat(1024 * 1024)) }
        val output = WorkspaceFileAccess(root).search(".", "absent", 50).text()
        assertTrue(output.contains("读取 8388608 字节"))
        assertTrue(output.contains("结果不完整"))
        var ticks = 0L
        val timed = WorkspaceTextSearch { ticks.also { ticks += 6_000_000_000L } }.search(root, "absent", 50)
        assertTrue(timed.contains("结果不完整"))
    }

    @Test fun invalidInputsAndCancellationDoNotBecomeSuccessfulEmptySearch() = runBlocking<Unit> {
        val operations = WorkspaceFileAccess(root())
        for (query in listOf("", "x\ny", "x".repeat(257))) {
            assertTrue(operations.search(".", query, 50) is AppResult.Failure)
        }
        assertTrue(operations.search(".", "x", 101) is AppResult.Failure)
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) { operations.search(".", "x", 50) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun failedEntryDoesNotStopLaterReadableFilesOrDiscardTheirMatches() = runBlocking<Unit> {
        val root = root()
        val first = root.put("first.txt", "needle first")
        val second = root.put("second.txt", "needle second")
        val engine = WorkspaceTextSearch(walk = { base, visitor ->
            assertEquals(FileVisitResult.CONTINUE, visitor.visitFileFailed(base.resolve("unreadable"), IOException("denied")))
            for (file in listOf(first, second)) {
                assertEquals(FileVisitResult.CONTINUE, visitor.visitFile(file.toPath(), Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)))
            }
        })
        val output = engine.search(root, "needle", 50)
        assertTrue(output.contains("命中 2"))
        assertTrue(output.contains("跳过 1 项"))
        assertTrue(output.contains("结果不完整"))
        assertTrue(output.contains("first.txt:1: needle first"))
        assertTrue(output.contains("second.txt:1: needle second"))
    }

    @Test fun readBackendDispatchesQueryAndDoesNotReadAsImageOrPaginatedFile() = runBlocking<Unit> {
        val root = root().apply { put("image.png", "literal text needle") }
        val backend = WorkspaceToolBackend({ WorkspaceFileAccess(root) })
        val args = Json.parseToJsonElement("""{"path":"image.png","query":"needle","max_results":1}""").jsonObject
        val result = backend.execute(WorkspaceToolRequest(HarnessTool.READ, args, "session", "/workspace"))
        assertTrue(result.success)
        assertTrue(result.output.contains("image.png:1:"))
        assertFalse(result.metadata.containsKey("image_payload"))
        try {
            backend.execute(WorkspaceToolRequest(HarnessTool.READ,
                Json.parseToJsonElement("""{"path":".","query":"needle","offset":1}""").jsonObject, "session", "/workspace"))
            fail("Ambiguous pagination must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
