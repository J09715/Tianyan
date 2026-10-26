plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.runtime"
    defaultConfig { consumerProguardFiles("ssh-consumer-rules.pro") }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":project-template"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.xz)
    implementation(libs.okhttp)
    implementation(libs.bundles.shizuku)
    implementation(libs.hiddenapi.bypass)
    implementation(libs.kadb)
    // Shower 虚拟屏客户端库（LGPL-3.0 来源）：虚拟屏 + shell 级输入注入 + H.264 视频回传
    implementation(project(":showerclient"))
    // Termux VT100 emulator + PTY JNI fork (GPL-3.0). Exported so feature/terminal can attach TerminalView.
    api(project(":termux-terminal-emulator"))
    testImplementation(libs.junit)
    // Bridge auth convergence tests need SettingsDataStore's SecretManager to build RuntimePreferences.
    testImplementation(project(":core:security"))
    testImplementation(libs.bundles.test.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.apache.sshd:sshd-core:2.16.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.16.0")
}
