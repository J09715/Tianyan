plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// Robolectric 4.17 在 JDK >= 17 上必须显式开放 OpenJDK 内部包，否则 sandbox 的
// AndroidInterceptors 反射注入时抛 IllegalAccessException（CI 实测：
// EnvironmentDoctorTest 整组 6 个用例全红，栈顶 AndroidInterceptors.java:88）。
// 4.16.1 不需要；4.17 的 release note 原文是「you may need to add jvmFlags
// configuration when using JDKs >= 17」，列表取自 robolectric.org/getting-started。
//
// 放在根 subprojects 而不是各模块 testOptions：所有单测任务（含将来新增模块）
// 一次覆盖，避免「新模块忘了加」这种复发。对非 Robolectric 测试无副作用。
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.security=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        )
    }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Checks module direction and persistence boundaries."

    inputs.files(
        // 排除各模块 build/ 输出目录：否则与其他任务并发写盘时，
        // Gradle 9 的严格校验会报隐式依赖错误导致 architectureCheck 直接失败
        fileTree("feature") {
            include("**/*.kt", "**/build.gradle.kts")
            exclude("**/build/**")
        },
        fileTree("runtime/src/main") { include("**/*.kt") },
        fileTree("harness/src/main") { include("**/*.kt") },
        file("core/model/build.gradle.kts"),
        file("core/common/build.gradle.kts"),
    )

    doLast {
        val violations = mutableListOf<String>()
        val modelBuild = file("core/model/build.gradle.kts").readText()
        listOf("android.library", "hilt", "androidx.core").forEach { forbidden ->
            if (forbidden in modelBuild) violations += "core:model must stay platform-free: $forbidden"
        }
        if ("project(\":core:security\")" in file("core/common/build.gradle.kts").readText()) {
            violations += "core:common must not depend on the concrete security module"
        }

        val sharedFeatureTargets = setOf("components", "theme")
        file("feature").listFiles().orEmpty().filter { it.isDirectory }.forEach { moduleDir ->
            val buildFile = moduleDir.resolve("build.gradle.kts")
            if (!buildFile.isFile || moduleDir.name == "navigation") return@forEach
            Regex("project\\(\\\":feature:([^\\\"]+)\\\"\\)")
                .findAll(buildFile.readText())
                .map { it.groupValues[1] }
                .filter { target ->
                    target !in sharedFeatureTargets || (moduleDir.name == "theme") ||
                        (moduleDir.name == "components" && target != "theme")
                }
                .forEach { target -> violations += "feature:${moduleDir.name} must not depend on feature:$target" }
        }

        val forbiddenDaoImport = Regex("import\\s+top\\.J09715\\.tianyan\\.core\\.database\\.\\w+Dao")
        listOf(file("feature"), file("runtime/src/main"), file("harness/src/main")).forEach { sourceRoot ->
            sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
                if (forbiddenDaoImport.containsMatchIn(source.readText())) {
                    violations += "${source.relativeTo(rootDir)} imports a Room DAO; depend on a repository port"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString(prefix = "Architecture violations:\n- ", separator = "\n- "))
        }
    }
}
