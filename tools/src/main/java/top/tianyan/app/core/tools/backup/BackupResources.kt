package top.tianyan.app.core.tools.backup

import kotlinx.serialization.json.*
import top.tianyan.app.core.database.HarnessBlobStore
import top.tianyan.app.core.model.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class BackupLocations(val files: File, val attachments: File, val workspace: File, val cache: File)

internal class BackupResources(private val locations: BackupLocations) {
    fun collect(records: BackupRecords, stage: File): Pair<BackupRecords, List<BackupAsset>> {
        val assets = linkedMapOf<String, BackupAsset>()
        var total = 0L
        fun add(source: File, path: String) {
            BackupArchive.safePath(path)
            require(!Files.isSymbolicLink(source.toPath()) && source.isFile && source.length() <= BackupArchive.MAX_FILE_BYTES) { "备份资源缺失、过大或为符号链接" }
            require(assets.size < BackupArchive.MAX_FILES && path !in assets) { "备份资源数量超限或重复" }
            val target = File(stage, path)
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192); var size = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; total += n
                    require(size <= BackupArchive.MAX_FILE_BYTES && total <= BackupArchive.MAX_BYTES) { "备份资源超过上限" }; output.write(buffer, 0, n)
                }
            } }
            assets[path] = BackupAsset(path, target.length(), BackupArchive.digest(target))
        }
        BackupArchive.files(locations.attachments).forEach { add(it, "attachments/" + it.relativeTo(locations.attachments).invariantSeparatorsPath) }
        val tables = records.tables.mapValues { (table, rows) -> rows.map { row ->
            val changed = row.toMutableMap()
            if (table == "agent_skills") {
                val resource = row["resourcePath"]?.jsonPrimitive?.contentOrNull
                if (!resource.isNullOrBlank()) {
                    val source = File(resource).canonicalFile
                    require(source.isDirectory && listOf(locations.attachments, locations.workspace).any {
                        source.toPath().startsWith(it.canonicalFile.toPath()) && source != it.canonicalFile
                    }) { "技能资源不在应用工作区内，无法完整备份" }
                    val id = hash(row.getValue("id").jsonPrimitive.content)
                    val path = "skills/$id"
                    BackupArchive.files(source).forEach { add(it, "$path/" + it.relativeTo(source).invariantSeparatorsPath) }
                    require(assets.keys.any { it.startsWith("$path/") }) { "技能资源目录为空" }
                    changed["resourcePath"] = JsonPrimitive("backup:$path")
                    val guest = if (source.toPath().startsWith(locations.attachments.canonicalFile.toPath()))
                        "/attachments/" + source.relativeTo(locations.attachments.canonicalFile).invariantSeparatorsPath
                        else "/workspace/" + source.relativeTo(locations.workspace.canonicalFile).invariantSeparatorsPath
                    changed["systemPrompt"] = JsonPrimitive(row.getValue("systemPrompt").jsonPrimitive.content
                        .replace(guest, "/attachments/__backup__/$path").replace(resource, "backup:$path"))
                }
            }
            if (table == "harness_entries") {
                val payload = row.getValue("payloadJson").jsonPrimitive.content
                if (payload.startsWith(HarnessBlobStore.BLOB_PREFIX)) {
                    val source = File(locations.files, payload.removePrefix(HarnessBlobStore.BLOB_PREFIX))
                    require(source.canonicalFile.toPath().startsWith(locations.files.canonicalFile.toPath())) { "聊天载荷路径无效" }
                    val path = "harness_blobs/${hash(row.getValue("id").jsonPrimitive.content)}.json"
                    add(source, path)
                    changed["payloadJson"] = JsonPrimitive(HarnessBlobStore.BLOB_PREFIX + "backup:$path")
                }
            }
            JsonObject(changed)
        } }
        return BackupRecords(tables) to assets.values.toList()
    }

    fun materialize(records: BackupRecords, manifest: LocalBackupManifest, root: File): BackupRecords {
        val guest = "/attachments/${root.name}"
        fun rewrite(text: String): String = text
            .replace("/attachments/__backup__/", "__BACKUP_SKILLS__/")
            .replace(manifest.sourceAttachments, "__BACKUP_ATTACHMENT_ROOT__")
            .replace("/attachments/", "__BACKUP_GUEST_ATTACHMENTS__/")
            .replace(manifest.sourceWorkspace, locations.workspace.absolutePath)
            .replace("__BACKUP_ATTACHMENT_ROOT__", File(root, "attachments").absolutePath)
            .replace("__BACKUP_GUEST_ATTACHMENTS__", "$guest/attachments")
            .replace("__BACKUP_SKILLS__", guest)
        return BackupRecords(records.tables.mapValues { (table, rows) -> rows.map { row ->
            JsonObject(row.mapValues { (_, value) -> if (value is JsonPrimitive && value.isString) JsonPrimitive(rewrite(value.content)) else value }
                .toMutableMap().apply {
                    if (table == "agent_skills") get("resourcePath")?.jsonPrimitive?.contentOrNull?.let { path ->
                        if (path.startsWith("backup:skills/")) {
                            val relative = path.removePrefix("backup:")
                            BackupArchive.safePath(relative)
                            require(manifest.assets.any { it.path.startsWith("$relative/") }) { "技能资源清单不完整" }
                            put("resourcePath", JsonPrimitive(File(root, relative).absolutePath))
                            put("systemPrompt", JsonPrimitive(getValue("systemPrompt").jsonPrimitive.content.replace(path, File(root, relative).absolutePath)))
                        } else require(path.isBlank()) { "技能资源引用无效" }
                    }
                    if (table == "harness_entries") {
                        val payload = row.getValue("payloadJson").jsonPrimitive.content
                        if (payload.startsWith(HarnessBlobStore.BLOB_PREFIX)) {
                            val relative = payload.removePrefix(HarnessBlobStore.BLOB_PREFIX + "backup:")
                            require(manifest.assets.any { it.path == relative } && relative.startsWith("harness_blobs/")) { "聊天载荷资源缺失" }
                            put("payloadJson", JsonPrimitive(HarnessBlobStore.BLOB_PREFIX + File(root, relative).relativeTo(locations.files).path))
                        }
                    }
                    if (table == "workspaces") {
                        val name = getValue("name").jsonPrimitive.content
                        BackupArchive.safePath(name); require('/' !in name) { "工作区名称无效" }
                        put("path", JsonPrimitive(File(locations.workspace, name).absolutePath)); put("ownsDirectory", JsonPrimitive(0))
                    }
                })
        } })
    }

    fun rewriteBlobs(root: File, manifest: LocalBackupManifest) {
        // Referenced large payload files contain the same absolute/guest attachment paths as inline messages.
        manifest.assets.filter { it.path.startsWith("harness_blobs/") }.forEach {
            val file = File(root, it.path)
            val text = file.readText().replace(manifest.sourceAttachments, File(root, "attachments").absolutePath)
                .replace("/attachments/", "/attachments/${root.name}/attachments/")
            file.writeText(text)
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
