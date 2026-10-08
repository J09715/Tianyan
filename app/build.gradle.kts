import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val appVersionName = "0.17.5"
val appVersionCode = 63

// TianyanDev 双包构建开关：CI（.github/workflows/tianyandev-build.yml）设 TIANYAN_DEV_BUILD=1 时，
// 产出独立预览包 top.tianyan.app.dev / 应用名 TianyanDev / 版本后缀 -dev，
// 与正式版（top.tianyan.app）及本地调试包（top.tianyan.app.debug）完全共存互不干扰。
val tianyanDevBuild = System.getenv("TIANYAN_DEV_BUILD") == "1"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

extensions.configure<ApplicationExtension> {
    namespace = "top.tianyan.app"
    resourcePrefix = "tianyan_"
    compileSdk = 37
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = if (tianyanDevBuild) "top.tianyan.app.dev" else "top.tianyan.app"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        // 应用名统一走 manifest placeholder：TianyanDev 构建显示 "TianyanDev"，其余显示 "天衍"。
        manifestPlaceholders["appLabel"] = if (tianyanDevBuild) "TianyanDev" else "天衍"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: project.file("keystore.properties").takeIf { it.exists() }
    val keystoreProperties = Properties()
    if (keystorePropertiesFile != null) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    fun signingValue(environmentVariable: String, propertyName: String): String? =
        System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
            ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

    val signingStoreFilePath = signingValue("TIANYAN_RELEASE_STORE_FILE", "storeFile")
    val signingStorePassword = signingValue("TIANYAN_RELEASE_STORE_PASSWORD", "storePassword")
    val signingKeyAlias = signingValue("TIANYAN_RELEASE_KEY_ALIAS", "keyAlias")
    val signingKeyPassword = signingValue("TIANYAN_RELEASE_KEY_PASSWORD", "keyPassword")
    val signingValues = listOf(
        signingStoreFilePath,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword,
    )
    //noinspection WrongGradleMethod
    val signingRequested = signingValues.any { it != null }
    //noinspection WrongGradleMethod
    val signingConfigured = signingValues.all { it != null }
    check(!signingRequested || signingConfigured) {
        "Release signing is only partially configured. Provide all TIANYAN_RELEASE_* environment variables " +
            "or all entries in keystore.properties."
    }

    signingConfigs {
        create("release") {
            if (signingConfigured) {
                val storeFilePath = requireNotNull(signingStoreFilePath)
                val resolvedStoreFile = if (storeFilePath.startsWith("/") || storeFilePath.contains(":\\")) {
                    file(storeFilePath)
                } else {
                    rootProject.file(storeFilePath).takeIf { it.exists() } ?: project.file(storeFilePath)
                }
                storeFile = resolvedStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // TianyanDev 双包构建：包名与应用名已在 defaultConfig 按 tianyanDevBuild 分流，
            // 此处只控制后缀——本地调试包保持 top.tianyan.app.debug/-debug，
            // TianyanDev 预览包（top.tianyan.app.dev）不再叠加额外后缀，版本后缀为 -dev。
            if (!tianyanDevBuild) {
                applicationIdSuffix = ".debug"
            }
            versionNameSuffix = if (tianyanDevBuild) "-dev" else "-debug"
            // CI supplies the same keystore for every preview/debug APK so upgrades
            // remain installable instead of silently using the runner's ephemeral key.
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            manifestPlaceholders["appLabel"] = if (tianyanDevBuild) "TianyanDev" else "天衍"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            // PRoot is launched as an extracted ARM64 executable on Android 10+.
            useLegacyPackaging = true
            // Tianyan only supports arm64-v8a; Android AARs may also publish legacy/x86 ABIs.
            excludes += listOf(
                "**/armeabi-v7a/*.so",
                "**/x86/*.so",
                "**/x86_64/*.so",
            )
            // The PRoot tracee loader is an executable payload, not a JNI library.
            // Preserve the official package bytes instead of running AGP's strip tool.
            keepDebugSymbols += "**/libproot-loader.so"
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/license.txt",
                "META-INF/notice.txt",
            )
            // bcprov 被无线 ADB 密钥栈使用，但其后量子密码(pqc)子模块（含 picnic
            // lowmc 表 ~1.2MB 二进制 .properties 资源，R8 不清理资源）全项目零引用，
            // 参照 taixu 7526e16 减包思路剔除（对齐其"离线只留必需"策略）。
            excludes += "org/bouncycastle/pqc/**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    // Baseline Profile 运行时安装器：首帧前将打包进 APK 的 profile 提交给 ART 预编译。
    implementation(libs.androidx.profileinstaller)
    // 生成者模块：generateBaselineProfile 时由此拉起 macrobenchmark 采集
    baselineProfile(project(":baselineprofile"))
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(project(":feature:components"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:theme"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.shizuku.provider)

    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation(libs.zstd) {
        artifact { type = "aar" }
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val bundledProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot.so",
)
val bundledProotLoader = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot-loader.so",
)
val bundledPtyNative = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libpty_native.so",
)
val bundledRtk = layout.projectDirectory.file("src/main/assets/bin/rtk")
val bundledRtkSha256 = "ce9a4847940ea26169df818d6907cd99bac0257a59ed4cc6c4b647e41277ad94"

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(rootProject.tasks.named("architectureCheck"))
        doFirst {
            check(bundledProot.asFile.isFile && bundledProot.asFile.length() > 4096L) {
                "Missing ARM64 PRoot tracer. Run tools/prepare-proot-runtime.ps1 before building."
            }
            check(bundledProotLoader.asFile.isFile && bundledProotLoader.asFile.length() > 4096L) {
                "Missing ARM64 PRoot loader. Run tools/prepare-proot-runtime.ps1 before building."
            }
            if (bundledRtk.asFile.exists()) {
                check(bundledRtk.asFile.isFile && bundledRtk.asFile.length() > 1_000_000L) {
                    "Bundled RTK executable in app/src/main/assets/bin/rtk is corrupted or too small."
                }
                val rtkBytes = bundledRtk.asFile.readBytes()
                check(
                    rtkBytes.size >= 20 &&
                        rtkBytes[0] == 0x7F.toByte() && rtkBytes[1] == 'E'.code.toByte() &&
                        rtkBytes[2] == 'L'.code.toByte() && rtkBytes[3] == 'F'.code.toByte() &&
                        rtkBytes[18] == 0xB7.toByte() && rtkBytes[19] == 0x00.toByte(),
                ) {
                    "Bundled RTK must be an ELF AArch64 Linux executable."
                }
                val rtkSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(rtkBytes)
                    .joinToString("") { "%02x".format(it) }
                check(rtkSha256 == bundledRtkSha256) {
                    "Bundled RTK SHA-256 mismatch. Expected $bundledRtkSha256, got $rtkSha256."
                }
            }
            // libpty_native 必须是 NDK/Bionic 构建：若依赖 glibc 的 libc.so.6，设备上
            // dlopen 必失败并静默回退到 script PTY 路径（PTY 回显问题会随之复发）。
            //
            // 但它是构建产物（.gitignore 不入库，由 NDK 从 app/src/main/cpp/pty_native.c
            // 重建），所以只在真正产出 APK 的构建里强制要求。挂在 preBuild 上无条件
            // 检查会让 `./gradlew test` 因为一个与测试无关的原生库缺失而整条失败，
            // 本地根本跑不了单元测试。
            // 用 AGP 的原生库合并任务作为"正在产出 APK"的判据。
            // 不能靠任务名前缀猜：:app:testDebugUnitTest 的依赖里既有
            // packageDebugUnitTestForUnitTest，也有 bundleDebugClassesToRuntimeJar
            // 和 packageDebugResources，按 assemble/bundle/package 前缀判断会把跑测试
            // 误判成打包，于是本地和 CI 的 `./gradlew test` 全部失败。
            // merge<Variant>JniLibFolders 只在真正打包 APK 时才进入任务图。
            val packagingApk = gradle.taskGraph.allTasks.any { task ->
                task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")
            }
            if (packagingApk) {
                check(bundledPtyNative.asFile.isFile) {
                    "Missing libpty_native.so. Build it with the NDK clang before assembling " +
                        "(aarch64-linux-android30-clang -shared -fPIC -O2 -o <out> app/src/main/cpp/pty_native.c). " +
                        "CI does this automatically."
                }
                val ptyNativeBytes = bundledPtyNative.asFile.readBytes()
                val glibcMarker = "libc.so.6".toByteArray()
                check(ptyNativeBytes.size < glibcMarker.size ||
                    (0..ptyNativeBytes.size - glibcMarker.size).none { offset ->
                        glibcMarker.indices.all { ptyNativeBytes[offset + it] == glibcMarker[it] }
                    }) {
                    "libpty_native.so is linked against glibc (libc.so.6). Rebuild it with the NDK " +
                        "aarch64-linux-android clang (see app/src/main/cpp/CMakeLists.txt)."
                }
            }
        }
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val buildAppName = "tianyan-v${appVersionName}-${variant.name}.apk"
        variant.outputs.forEach { output ->
            output.outputFileName.set(buildAppName)
        }
    }
}
