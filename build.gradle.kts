import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Gradle 9 的 Kotlin DSL 默认编译类路径不再暴露 Groovy JSON 模块，
// architectureCheck 解析 architecture-policy.json 需要显式引入（仅构建期，不进入产物）。
// 本块是独立于 settings.gradle.kts 的 buildscript 解析域：settings 里的
// useOfficialRepos 不作用于这里，因此必须自带同样的 CI 判断，否则 CI 仍会
// 命中阿里云镜像。注意 AGP 的 classpath 传递依赖 com.android.tools:repository
// 等 Google 专有产物在 aliyun central 上并不存在（实测该 pom 在 repository/central
// 恒为 404，在 repository/google 为 200）。平时靠更靠后的 google() 兜底才没炸；
// 一旦 aliyun central 返回 5xx，Gradle 会把该仓库标记为 disabled 并直接中止配置
// 阶段（"Repository maven is disabled due to earlier error"），故 google() 必须
// 显式列出。
// 注意：buildscript {} 是独立的脚本编译单元，引用不到本文件的顶层 val，
// 因此这里的判断必须在块内内联展开，不能提取成共享变量。
buildscript {
    repositories {
        val ciOfficial = System.getenv("CI") == "true" ||
            providers.gradleProperty("useOfficialRepos").orNull == "true" ||
            System.getenv("USE_OFFICIAL_REPOS") == "true"
        if (ciOfficial) {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            google()
            mavenCentral()
        }
    }
    dependencies {
        classpath("org.apache.groovy:groovy-json:4.0.27")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// Older transitive annotation-experimental lint checks report false
// InternalSerializationApi opt-in errors for generated @Serializable code.
// https://youtrack.jetbrains.com/issue/KTIJ-31549
val annotationExperimentalMinVersion = libs.versions.annotationExperimental.get()
subprojects {
    listOf("com.android.application", "com.android.library", "com.android.test").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            dependencies.constraints.add(
                "implementation",
                "androidx.annotation:annotation-experimental:$annotationExperimentalMinVersion",
            ) {
                because("1.5.1 fixes false InternalSerializationApi opt-in reports in Android Lint")
            }
        }
    }
}

// ==================== 架构治理（policy 即代码） ====================
// 规则唯一事实源：architecture-policy.json
//   - 模块依赖白名单 requires（补齐反向依赖检查：runtime/harness/tools/core 依赖 feature 即失败）
//   - 依赖图无环（forbidCycles）
//   - import 黑名单 importBans（模型层纯 Kotlin / 业务层禁直连 Room DAO）
//   - 文件尺寸棘轮 global.maxFileLines + .architecture-baseline.json（存量超限文件只许缩减不许上涨）
// 基线维护：运行 architectureBaselineSync（收缩下调 / 拒绝上涨 / 清理失效条目）。
val architecturePolicyFile = rootProject.file("architecture-policy.json")

fun parseJsonMap(file: java.io.File): Map<*, *> =
    JsonSlurper().parseText(file.readText().trim().removePrefix("\uFEFF")) as Map<*, *>

fun policyModules(policy: Map<*, *>): List<Map<*, *>> =
    @Suppress("UNCHECKED_CAST")
    (policy["modules"] as List<Map<*, *>>)

fun moduleGradlePath(module: Map<*, *>): String =
    ":${(module["path"] as String).replace('/', ':')}"

fun moduleSourceRoot(module: Map<*, *>): java.io.File =
    rootDir.resolve(module["path"] as String).resolve("src/main")

// 配置期解析一次 policy，供两个验证任务登记精确输入。
// 输入必须显式枚举到「policy json + 各模块 build.gradle.kts + src/main/**/*.kt」，
// 不能用 fileTree(rootDir)：其根覆盖各模块 build/ 输出目录，Gradle 9 会把
// architectureCheck 判定为隐式依赖 ':x:compileKotlin' 的输出（uses this output
// without declaring an explicit dependency），与编译任务同图时直接构建失败。
val architecturePolicyData = parseJsonMap(architecturePolicyFile)
val architectureBaselineFile = rootProject.file(
    (architecturePolicyData["global"] as Map<*, *>)["baselineFile"] as String,
)
val architectureModuleDirs = policyModules(architecturePolicyData)
    .map { rootDir.resolve(it["path"] as String) }

// Separate read-only check: Python is required only when this explicit task is requested.
tasks.register<Exec>("architectureBaselineCheck") {
    group = "verification"
    description = "检查尺寸棘轮收紧提示，并相对可信 Git 基准阻止额度放宽和已偿还技术债回涨。"
    workingDir(rootDir)
    val python = providers.gradleProperty("architecturePython").orElse(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
    )
    commandLine(python.get(), "tools/check-architecture-baseline.py")
    providers.gradleProperty("architectureBaselineBaseRef").orNull?.let { args("--base-ref", it) }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Policy-driven checks: dependency whitelist, cycles, import bans, size ratchet."
    // Sync writes the baseline this check reads; order them when both are explicitly requested.
    mustRunAfter("architectureBaselineSync")

    inputs.files(architecturePolicyFile)
    if (architectureBaselineFile.isFile) inputs.files(architectureBaselineFile)
    architectureModuleDirs.forEach { moduleDir ->
        inputs.files(moduleDir.resolve("build.gradle.kts"))
        inputs.files(fileTree(moduleDir.resolve("src/main")) { include("**/*.kt") })
    }

    doLast {
        val policy = parseJsonMap(architecturePolicyFile)
        val global = policy["global"] as Map<*, *>
        val maxFileLines = (global["maxFileLines"] as Number).toInt()
        val baselineFile = rootProject.file(global["baselineFile"] as String)

        val modules = policyModules(policy)
        val idByGradlePath = modules.associate { moduleGradlePath(it) to it["id"] as String }
        val moduleById = modules.associateBy { it["id"] as String }

        val violations = mutableListOf<String>()
        // 捕获至引号前：模块名可含连字符（如 project-template）
        val projectDepRegex = Regex("""project\(":([^"\s]+)""")

        // ---- 规则 1：模块依赖白名单 + 未登记依赖 ----
        val declaredDeps = mutableMapOf<String, List<String>>()
        modules.forEach { module ->
            val id = module["id"] as String
            val buildFile = rootDir.resolve(module["path"] as String).resolve("build.gradle.kts")
            if (!buildFile.isFile) {
                violations += "policy 模块 $id 的构建脚本不存在: ${buildFile.relativeTo(rootDir)}"
                return@forEach
            }
            // 剥离 // 注释行，避免注释中的示例引用被当作依赖
            val buildText = buildFile.readLines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            val allowed = (module["requires"] as List<*>).map { it as String }.toSet()
            val deps = mutableListOf<String>()
            projectDepRegex.findAll(buildText).forEach { match ->
                val depId = idByGradlePath[":${match.groupValues[1]}"]
                when {
                    depId == null ->
                        violations += "模块 $id 依赖了未登记的模块 :${match.groupValues[1]}（请同步 architecture-policy.json）"
                    depId !in allowed ->
                        violations += "模块 $id 依赖 $depId，不在其 requires 白名单中"
                    else -> deps += depId
                }
            }
            declaredDeps[id] = deps
            // 构建脚本字符串黑名单（如模型层禁平台插件）
            (module["forbidBuildScript"] as? List<*>)?.forEach { forbidden ->
                if (buildText.contains(forbidden as String)) {
                    violations += "模块 $id 构建脚本出现被禁止的声明: $forbidden"
                }
            }
        }

        // ---- 规则 2：依赖图无环（DFS 三色标记）----
        if (global["forbidCycles"] == true) {
            val state = mutableMapOf<String, Int>() // 1=在栈中 2=已完成
            fun dfs(node: String, stack: List<String>) {
                when (state[node]) {
                    1 -> {
                        val start = stack.indexOf(node).coerceAtLeast(0)
                        violations += "模块依赖环: ${(stack.drop(start) + node).joinToString(" -> ")}"
                        return
                    }
                    2 -> return
                }
                state[node] = 1
                declaredDeps[node].orEmpty().forEach { dfs(it, stack + node) }
                state[node] = 2
            }
            declaredDeps.keys.forEach { if (state[it] == null) dfs(it, emptyList()) }
        }

        // ---- 规则 3：import 黑名单（逐行匹配 src/main 下 .kt）----
        val groupMembers = (policy["groups"] as? Map<*, *>)
            ?.mapValues { (_, v) -> (v as List<*>).map { it as String }.toSet() }
            ?: emptyMap()
        (policy["importBans"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { ban ->
            val banId = ban["id"] as String
            val pattern = Regex(ban["pattern"] as String)
            val message = ban["message"] as? String ?: "命中 import 黑名单 $banId"
            val targets = mutableSetOf<String>()
            (ban["modules"] as? List<*>)?.forEach { targets += it as String }
            (ban["groups"] as? List<*>)?.forEach { g -> targets += groupMembers[g as String].orEmpty() }
            targets.forEach { targetId ->
                val module = moduleById[targetId]
                if (module == null) {
                    violations += "importBans[$banId] 引用了未定义模块 $targetId"
                    return@forEach
                }
                val srcRoot = moduleSourceRoot(module)
                if (!srcRoot.isDirectory) return@forEach
                srcRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { source ->
                        source.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (pattern.containsMatchIn(line)) {
                                    violations += "${source.relativeTo(rootDir).invariantSeparatorsPath}:${index + 1}: $message"
                                }
                            }
                        }
                    }
            }
        }

        // ---- 规则 4：文件尺寸棘轮（基线豁免存量，只许缩减）----
        val baselineFiles: Map<*, *> = if (baselineFile.isFile) {
            (parseJsonMap(baselineFile)["files"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        } else {
            emptyMap<Any?, Any?>()
        }
        modules.forEach { module ->
            val srcRoot = moduleSourceRoot(module)
            if (!srcRoot.isDirectory) return@forEach
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    val lineCount = source.useLines { it.count() }
                    if (lineCount > maxFileLines) {
                        val rel = source.relativeTo(rootDir).invariantSeparatorsPath
                        val baselineValue = (baselineFiles[rel] as? Number)?.toInt()
                        when {
                            baselineValue == null ->
                                violations += "$rel 共 $lineCount 行，超过 maxFileLines=$maxFileLines（新文件必须合规；确属存量请运行 architectureBaselineSync 登记）"
                            lineCount > baselineValue ->
                                violations += "$rel 增长到 $lineCount 行，超过棘轮基线 $baselineValue（基线只许下调，请先缩减该文件）"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString(prefix = "\nArchitecture violations (${violations.size}):\n- ", separator = "\n- ")
            )
        }
        println("architectureCheck: ${modules.size} 个模块 · 依赖白名单/无环/import 黑名单/尺寸棘轮 全部通过")
    }
}

tasks.register("architectureBaselineSync") {
    group = "verification"
    description = "同步文件尺寸棘轮基线：收缩下调 / 拒绝上涨 / 清理失效条目。"

    inputs.files(architecturePolicyFile)
    if (architectureBaselineFile.isFile) inputs.files(architectureBaselineFile)
    architectureModuleDirs.forEach { moduleDir ->
        inputs.files(fileTree(moduleDir.resolve("src/main")) { include("**/*.kt") })
    }
    outputs.file(rootProject.file(".architecture-baseline.json"))

    doLast {
        val policy = parseJsonMap(architecturePolicyFile)
        val global = policy["global"] as Map<*, *>
        val maxFileLines = (global["maxFileLines"] as Number).toInt()
        val baselineFile = rootProject.file(global["baselineFile"] as String)

        val baseline: MutableMap<Any?, Any?> = if (baselineFile.isFile) {
            @Suppress("UNCHECKED_CAST")
            (parseJsonMap(baselineFile)["files"] as? Map<Any?, Any?>)?.toMutableMap() ?: mutableMapOf()
        } else {
            mutableMapOf()
        }

        val oversized = mutableMapOf<String, Int>()
        policyModules(policy).forEach { module ->
            val srcRoot = moduleSourceRoot(module)
            if (!srcRoot.isDirectory) return@forEach
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    val lineCount = source.useLines { it.count() }
                    if (lineCount > maxFileLines) {
                        oversized[source.relativeTo(rootDir).invariantSeparatorsPath] = lineCount
                    }
                }
        }

        var added = 0
        var lowered = 0
        var refused = 0
        var cleaned = 0
        oversized.forEach { (rel, lines) ->
            val old = (baseline[rel] as? Number)?.toInt()
            when {
                old == null -> { baseline[rel] = lines; added++ }
                lines < old -> { baseline[rel] = lines; lowered++ }
                lines > old -> refused++
            }
        }
        baseline.keys.toList().forEach { key ->
            val rel = key as String
            val file = rootDir.resolve(rel)
            if (!file.isFile || rel !in oversized) {
                baseline.remove(key)
                cleaned++
            }
        }

        val doc = linkedMapOf<String, Any?>(
            "_doc" to listOf(
                "架构棘轮基线：收录存量超过 maxFileLines 的源文件（行数快照）。",
                "规则：条目行数只能下调（代码缩减后应下调或删除条目）；文件行数超过基线值 = 架构违规。",
                "不在基线中的新文件必须 <= architecture-policy.json 的 global.maxFileLines。",
                "维护：运行 .\\gradlew.bat architectureBaselineSync 自动同步。"
            ),
            "generatedAt" to java.time.LocalDate.now().toString(),
            "maxFileLines" to maxFileLines,
            "fileCount" to baseline.size,
            "files" to baseline.toSortedMap(compareBy { it.toString() })
        )
        baselineFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(doc)))
        println("architectureBaselineSync: 新增 $added · 下调 $lowered · 拒绝上涨 $refused · 清理 $cleaned · 基线条目 ${baseline.size}")
        if (refused > 0) {
            throw GradleException("有 $refused 个基线文件行数上涨（棘轮拒绝生效）。请先缩减这些文件，再重新运行 sync。")
        }
    }
}
