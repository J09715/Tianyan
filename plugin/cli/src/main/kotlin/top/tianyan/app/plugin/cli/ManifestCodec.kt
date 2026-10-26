package top.tianyan.app.plugin.cli

import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.ToolManifest

internal object ManifestCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(manifest: ToolManifest): String = json.encodeToString(ToolManifest.serializer(), manifest)
}
