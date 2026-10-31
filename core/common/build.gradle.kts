plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.core.common"
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mlkit.translate)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
