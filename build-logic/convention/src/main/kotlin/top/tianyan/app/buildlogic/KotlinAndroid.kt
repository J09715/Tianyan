package top.tianyan.app.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension,
) {
    commonExtension.apply {
        compileSdk = 37
        compileSdkMinor = 1

        defaultConfig.apply {
            minSdk = 29
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions.apply {
            unitTests.isIncludeAndroidResources = true
        }

        if (this is LibraryExtension) {
            val segments = path.split("""\W""".toRegex()).filter { it.isNotBlank() }
            if (segments.isNotEmpty()) {
                resourcePrefix = segments.last().lowercase() + "_"
            }
        }
    }

    configureKotlin()
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
        sourceSets.named("main") {
            kotlin.srcDir("src/main/java")
        }
        sourceSets.findByName("test")?.let {
            it.kotlin.srcDir("src/test/java")
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    configureKotlin()
}

private fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
