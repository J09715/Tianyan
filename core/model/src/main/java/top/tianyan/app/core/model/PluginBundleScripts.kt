package top.tianyan.app.core.model

/**
 * 开发套件组件的安装 / 重新安装 / 卸载脚本。
 * 删除路径和软件包名都来自内置清单，并再过一层白名单，避免拼出 `rm -rf /`。
 */
internal object PluginBundleScripts {
    private val packageName = Regex("^[a-z0-9][a-z0-9+.-]*$")
    private val safeDirectory = Regex(
        "^/opt/(android-sdk|flutter|jadx)$" +
            "|^/opt/gradle-8\\.14\\.2$" +
            "|^/opt/tianyan/android-sdk-tools$" +
            "|^/opt/tianyan/toolchains/android/(sdk-tools|jdk|ndk)$" +
            "|^/opt/tianyan/toolchains/rust$" +
            "|^/opt/tianyan/tools/android-suite-offline/(jadx|cmake)$",
    )
    private val safeFile = Regex(
        "^/(opt/tianyan/bin|opt/tianyan/tools/android-suite-offline/bin|usr/local/bin|usr/bin)/[A-Za-z0-9._+-]+$" +
            "|^/opt/tianyan/tools/android-suite-offline/lib/apktool\\.jar$" +
            // MCP 能力仓库的落位标记：只允许删除 /opt/tianyan/mcp/<组件 id>.ok 这种单层文件。
            "|^/opt/tianyan/mcp/[A-Za-z0-9._+-]+\\.ok$" +
            "|^/etc/profile\\.d/tianyan-(android|rust)\\.sh$" +
            "|^/root/\\.gradle/init\\.gradle$" +
            "|^/root/\\.gradle/init\\.d/tianyan-android-ndk\\.gradle$",
    )
    /**
     * 允许的卸载命令白名单。
     *
     * 包名段刻意只放行 npm 合法字符（含 scope 的 `@scope/name`），不放行空格之外的 shell 元字符，
     * 因此 `npm uninstall -g @colbymchenry/codegraph` 这类带 scope 的全局包可以安全卸载，
     * 而任何拼接了 `;`、`|`、`$(...)` 的变体都会在校验期被拒绝。
     */
    private val safePostUninstall = Regex("^npm uninstall -g [A-Za-z0-9@/._+-]+( [A-Za-z0-9@/._+-]+)* >/dev/null 2>&1 \\|\\| true$")

    fun installScript(selectedComponentIds: Set<String>, reinstall: Boolean): List<String> {
        val selected = components(selectedComponentIds)
        val allAptPackages = (BuiltinPluginBundles.baseRequiredPackages + selected.flatMap { it.aptPackages }).distinct()
        val steps = preparationSteps()
        if (allAptPackages.isNotEmpty()) {
            val packageArg = allAptPackages.joinToString(" ") { requirePackage(it) }
            val aptOpts = aptOptions()
            steps.add("DEBIAN_FRONTEND=noninteractive apt-get $aptOpts update -y || true")
            steps.add(
                "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $packageArg || " +
                    "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts -f install -y --no-install-recommends && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $packageArg",
            )
        }
        if (reinstall) {
            val ownPackages = selected.flatMap { it.aptPackages }
                .distinct()
                .filter { it !in BuiltinPluginBundles.baseRequiredPackages }
            if (ownPackages.isNotEmpty()) {
                val packageArg = ownPackages.joinToString(" ") { requirePackage(it) }
                steps.add(
                    "DEBIAN_FRONTEND=noninteractive apt-get ${aptOptions()} install -y --reinstall --no-install-recommends $packageArg",
                )
            }
        }
        steps.add(fdAliasStep())
        steps.add(batAliasStep())
        if (reinstall) steps.addAll(purgeSteps(selected, keepPaths = emptySet(), keepFiles = emptySet()))
        selected.forEach { steps.addAll(it.postInstallSteps) }
        return steps
    }

    fun uninstallScript(selectedComponentIds: Set<String>, retainedComponentIds: Set<String>): List<String> {
        val selected = components(selectedComponentIds)
        val retained = components(retainedComponentIds - selectedComponentIds)
        val removePackages = selected.flatMap { it.aptPackages }
            .distinct()
            .filter { pkg ->
                pkg !in BuiltinPluginBundles.baseRequiredPackages && retained.none { pkg in it.aptPackages }
            }
        val steps = preparationSteps()
        selected.flatMap { it.postUninstallSteps }.distinct().forEach { step ->
            require(safePostUninstall.matches(step)) { "拒绝执行未登记的卸载命令" }
            steps.add(step)
        }
        steps.addAll(
            purgeSteps(
                selected,
                keepPaths = retained.flatMap { it.purgePaths }.toSet(),
                keepFiles = retained.flatMap { it.purgeFiles + it.purgeLinks }.toSet(),
            ),
        )
        if ("openjdk-17-jdk-headless" in removePackages) {
            steps.add(
                "rm -f -- /usr/local/bin/java /usr/bin/java /usr/local/bin/javac /usr/bin/javac",
            )
        }
        if (removePackages.isNotEmpty()) {
            val packageArg = removePackages.joinToString(" ") { requirePackage(it) }
            steps.add("DEBIAN_FRONTEND=noninteractive apt-get remove -y $packageArg")
        }
        return steps
    }

    private fun components(ids: Set<String>): List<PluginComponent> =
        BuiltinPluginBundles.bundles.flatMap { it.components }.filter { it.id in ids }

    fun preparationSteps(): MutableList<String> = mutableListOf(
        "mkdir -p /etc/dpkg/dpkg.cfg.d /usr/bin /usr/sbin /usr/lib 2>/dev/null || true",
        "printf 'force-unsafe-io\\nforce-overwrite\\n' > /etc/dpkg/dpkg.cfg.d/tianyan-proot 2>/dev/null || true",
        "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock /usr/bin/*.dpkg-new /usr/sbin/*.dpkg-new /usr/lib/*.dpkg-new 2>/dev/null || true",
        "DEBIAN_FRONTEND=noninteractive dpkg --remove --force-remove-reinstreq --force-depends unzip java-wrappers 2>/dev/null || true",
        "DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true",
    )

    fun aptOptions(): String =
        "-o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 " +
            "-o Acquire::ForceIPv4=true -o Acquire::Languages=en"

    private fun fdAliasStep(): String =
        "mkdir -p /usr/local/bin; if ! command -v fd >/dev/null 2>&1 && command -v fdfind >/dev/null 2>&1; then ln -sf \"${'$'}(command -v fdfind)\" /usr/local/bin/fd; fi"

    private fun batAliasStep(): String =
        "mkdir -p /usr/local/bin; if ! command -v bat >/dev/null 2>&1 && command -v batcat >/dev/null 2>&1; then ln -sf \"${'$'}(command -v batcat)\" /usr/local/bin/bat; fi"

    private fun purgeSteps(
        selected: List<PluginComponent>,
        keepPaths: Set<String>,
        keepFiles: Set<String>,
    ): List<String> {
        val directories = selected.flatMap { it.purgePaths }.distinct().filter { it !in keepPaths }
        val files = selected.flatMap { it.purgeFiles + it.purgeLinks }.distinct().filter { it !in keepFiles }
        directories.forEach(::requireDirectory)
        files.forEach(::requireFile)
        val steps = mutableListOf<String>()
        if (directories.isNotEmpty()) steps.add("rm -rf -- ${directories.joinToString(" ")}")
        if (files.isNotEmpty()) steps.add("rm -f -- ${files.joinToString(" ")}")
        return steps
    }

    private fun requirePackage(name: String): String {
        require(packageName.matches(name)) { "拒绝卸载软件包：$name" }
        return name
    }

    private fun requireDirectory(path: String) {
        require(safeDirectory.matches(path)) { "拒绝删除目录：$path" }
    }

    private fun requireFile(path: String) {
        require(safeFile.matches(path)) { "拒绝删除文件：$path" }
    }
}
