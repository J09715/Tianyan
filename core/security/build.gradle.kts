plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.core.security"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
