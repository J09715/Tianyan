plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.core.network"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bundles.ktor.client)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // AppUpdateManagerTest：下载拒绝分支用本地 MockWebServer，Context 走 Robolectric。
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.test.robolectric)
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
