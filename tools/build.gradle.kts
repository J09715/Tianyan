plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.tools"
}

dependencies {
    // Ed25519 插件签名校验（PluginSignatureVerifier）：JDK 自带的 Ed25519 在 Android 上
    // 版本差异大，统一用 BouncyCastle 保证各 API level 行为一致。
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    api(project(":plugin:packages"))
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.bundles.test.robolectric)
}
