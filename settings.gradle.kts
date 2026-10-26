pluginManagement {
    includeBuild("build-logic")
    // CI（GitHub Actions 自动注入 CI=true）在海外，阿里云镜像同步滞后且访问慢；
    // 本地开发亦可通过 -PuseOfficialRepos=true 或 USE_OFFICIAL_REPOS=true 强制走官方源。
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        if (useOfficialRepos) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            gradlePluginPortal()
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
            google()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        // 本地仓库：托管第三方预编译 AAR（如 Termux terminal-emulator / terminal-view），
        // 避免 AGP 在 library 模块直接依赖本地 files(*.aar) 触发 hasLocalAarDeps 构建异常
        maven {
            url = uri(rootDir.resolve("repo"))
        }
        // A2UI alpha 的元数据与 AAR 在镜像上可能不同步；整个组固定走官方源，
        // 避免 Gradle 选中镜像元数据后因缺少 AAR 而无法回退到 google()。
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx\\.a2ui(\\..*)?")
                includeModule("androidx.compose.material3", "material3-a2ui")
            }
        }
        // materialkolor 6.0.0 在阿里云镜像只同步了 POM（缺 .module 与 AAR，子模块 -android 均 404）；
        // 固定走官方 Maven Central，与 a2ui 同理。
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.materialkolor") }
        }
        if (useOfficialRepos) {
            google()
            mavenCentral()
        } else {
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
            google()
            mavenCentral()
        }
        // Kadb 的 SPAKE2 Android 实现仅发布在 JitPack；限制到精确 group，避免扩大依赖解析面。
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "Tianyan"
include(":app")
include(":baselineprofile")
include(":core:common")
include(":core:model")
include(":plugin:api")
include(":plugin:runtime")
include(":plugin:packages")
include(":plugin:cli")
include(":plugin:ui")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:security")
include(":runtime")
include(":project-template")
include(":tools")
include(":harness")
include(":harness:core")
include(":feature:theme")
include(":feature:components")
include(":feature:home")
include(":feature:chat")
include(":feature:terminal")
include(":feature:workspace")
include(":feature:workflow")
include(":feature:settings")
include(":feature:developer")
include(":feature:custom_iteration")
include(":feature:onboarding")
include(":feature:navigation")
include(":core:browser")
include(":runtime:browser")
include(":feature:browser")
include(":feature:git")
include(":feature:bot")
include(":feature:a2uipoc")
// Shower 虚拟屏客户端库（源自 Operit，LGPL-3.0，并入后按 GPL-3.0 分发）；独立成模块以保持来源边界清晰。
include(":showerclient")
// Termux terminal-emulator fork（GPL-3.0，源自 termux-app v0.118.0）：为 TerminalSession 引入
// TerminalSessionBackend 缝隙，使远程 SSH PTY 复用原生渲染层；独立成模块以保持来源边界清晰。
include(":termux-terminal-emulator")
