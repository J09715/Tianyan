package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/** v2 package declarations are data, never Android permissions or executable native entrypoints. */
@Serializable
data class PluginPackageSpec(
    val id: String,
    val api: PackageApiRange,
    val execution: String,
    val extensions: List<PackageExtension> = emptyList(),
    val dependencies: List<PackageDependency> = emptyList(),
    val capabilities: List<PackageCapability> = emptyList(),
    val resources: List<PackageResource> = emptyList(),
    /** Every regular payload file must have a SHA-256 entry relative to payload/. */
    val integrity: Map<String, String> = emptyMap(),
    /** WEB assets are read directly by default; PROOT_STATIC selects a fixed host-owned server. */
    val webSource: String = "PACKAGE",
    /**
     * Service page sources name one installed PROCESS tool whose own local service serves the page.
     * Only the tool id is accepted; port, path, command and environment are resolved natively at open
     * time and never travel in the package. Required for a service source, absent for every other.
     */
    val serviceToolId: String? = null,
    val harness: PackageHarnessSpec? = null,
)

@Serializable data class PackageApiRange(val minimum: Int, val maximum: Int)
@Serializable data class PackageExtension(val id: String, val point: String)
@Serializable data class PackageDependency(val id: String, val minimumVersion: String, val maximumVersionExclusive: String? = null)
@Serializable data class PackageCapability(val name: String, val target: String = "")
@Serializable data class PackageResource(
    val key: String,
    val path: String,
    val sharing: String = "EXCLUSIVE",
    val contentSha256: String? = null,
    /** Global resources are retained; P3 never recursively deletes a declared global path. */
    val removal: String = "RETAIN",
)

/** Minimal installed-version metadata. Contains no scripts, env, credentials or executable payload. */
@Serializable
data class PluginInstallReceipt(
    val schemaVersion: Int = 1,
    val toolId: String,
    val pluginId: String,
    val version: String,
    val dependencies: List<PackageDependency> = emptyList(),
    val capabilities: List<PackageCapability> = emptyList(),
    val resources: List<PackageResource> = emptyList(),
    /** Package directory version; legacy installed versions may be command output banners. */
    val packageVersion: String? = null,
)
