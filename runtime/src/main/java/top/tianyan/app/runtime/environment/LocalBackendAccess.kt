package top.tianyan.app.runtime.environment

import top.tianyan.app.core.datastore.RemoteServerRepository

/** Guard the service entry as well as its UI so alternate install/uninstall routes cannot bypass it. */
class LocalBackendAccess(private val servers: RemoteServerRepository) {
    fun requireLocal() {
        check(servers.activeId.value == null) { "当前主系统不支持本机插件操作，请先在 Linux 系统管理中明确切回本地系统" }
    }
}
