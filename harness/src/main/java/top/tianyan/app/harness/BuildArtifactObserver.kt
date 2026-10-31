package top.tianyan.app.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import top.tianyan.app.runtime.environment.ExecutionFiles
import top.tianyan.app.runtime.environment.FileFingerprint
import top.tianyan.app.runtime.environment.MAX_FILE_FINGERPRINT_BYTES

/** A bounded inventory of standard APK output roots. Observations never prove goal behavior or causality. */
internal class BuildArtifactObserver(private val files: ExecutionFiles, private val base: String) {
    data class Inventory(val versions: Map<String, FileFingerprint>, val present: Set<String>, val complete: Boolean)

    suspend fun scan(): Inventory {
        val versions = linkedMapOf<String, FileFingerprint>()
        val present = linkedSetOf<String>()
        var complete = true
        var budget = SCAN_BYTES
        val finished = withTimeoutOrNull(10_000) {
            val directories = ArrayDeque<Pair<String, Int>>()
            ROOTS.forEach { directories += (if (base.isBlank()) it else "$base/$it") to 0 }
            var visited = 0
            while (directories.isNotEmpty()) {
                if (++visited > MAX_DIRECTORIES) { complete = false; break }
                val (directory, depth) = directories.removeFirst()
                val listing = try { files.listArtifactEntries(directory).getOrNull() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (listing == null) { complete = false; continue }
                for (entry in listing) {
                    if (entry.name.isBlank() || entry.name == "." || entry.name == ".." || entry.name.any { it == '/' || it == '\\' || it.isISOControl() }) {
                        complete = false; continue
                    }
                    val path = "$directory/${entry.name}"
                    if (entry.directory) {
                        if (depth >= 3 || directories.size >= MAX_DIRECTORIES) complete = false else directories += path to (depth + 1)
                    } else if (entry.name.endsWith(".apk", ignoreCase = true)) {
                        if (present.size >= ToolFileArtifacts.MAX_FILES) { complete = false; continue }
                        present += path
                        if (budget <= 0) { complete = false; continue }
                        val allowance = minOf(MAX_FILE_FINGERPRINT_BYTES, budget)
                        val fingerprint = try { files.fingerprint(path, allowance).getOrNull()?.takeIf {
                            it.bytes in 1..allowance && it.sha256.matches(Regex("[a-f0-9]{64}"))
                        } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (fingerprint == null) { budget -= allowance; complete = false }
                        else { budget -= fingerprint.bytes; versions[path] = fingerprint }
                    }
                }
            }
            true
        }
        return Inventory(versions, present, complete && finished == true)
    }

    fun after(before: Inventory, after: Inventory, now: Long): ToolFileArtifacts = ToolFileArtifacts(
        after.versions.map { (path, fingerprint) ->
            val old = before.versions[path]
            val origin = when {
                old?.sha256 == fingerprint.sha256 -> FileArtifactOrigin.UNCHANGED_AFTER_COMMAND
                old != null -> FileArtifactOrigin.CHANGED_AFTER_COMMAND
                before.complete && path !in before.present -> FileArtifactOrigin.NEW_AFTER_COMMAND
                else -> FileArtifactOrigin.OBSERVED_AFTER_COMMAND
            }
            ToolFileArtifacts.observation(path, fingerprint, origin, now)
        }, before.complete && after.complete,
    )

    companion object {
        private val ROOTS = listOf("app/build/outputs/apk", "build/outputs/apk", "build/app/outputs/flutter-apk")
        private const val MAX_DIRECTORIES = 32
        private const val SCAN_BYTES = 256L * 1024 * 1024
    }
}
