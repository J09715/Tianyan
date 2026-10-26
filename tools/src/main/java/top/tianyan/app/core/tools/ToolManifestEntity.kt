package top.tianyan.app.core.tools

import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.model.ToolState
import top.tianyan.app.core.database.ToolEntity

internal fun ToolManifest.toEntity(distroId: String, existing: ToolEntity?) = ToolEntity(
        distroId = distroId,
        id = id,
        name = name,
        description = description,
        dependencies = dependencies.joinToString(","),
        launchType = launchType,
        state = when {
            !enabled -> ToolState.DISABLED.name
            existing == null -> ToolState.AVAILABLE.name
            else -> {
                val installedVersion = existing.installedVersion
                val manifestLatest = latestVersion ?: version
                when {
                    existing.state == ToolState.DISABLED.name ->
                        if (installedVersion != null) ToolState.INSTALLED.name else ToolState.AVAILABLE.name
                    installedVersion != null && isUpdateNewer(manifestLatest, installedVersion) ->
                        ToolState.UPDATE_AVAILABLE.name
                    existing.state == ToolState.UPDATE_AVAILABLE.name -> ToolState.INSTALLED.name
                    else -> existing.state
                }
            }
        },
        manifestVersion = version,
        installedVersion = existing?.installedVersion,
        publisher = publisher,
        category = category,
        permissions = permissions.joinToString(","),
        homepage = homepage,
        updateStrategy = updateStrategy,
        latestVersion = latestVersion ?: version,
    )
