pluginManagement {
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
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
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
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
