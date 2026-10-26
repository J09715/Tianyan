plugins {
    alias(libs.plugins.tianyan.android.library.compose)
}

android {
    namespace = "top.tianyan.app.feature.theme"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.compose.ui)
    implementation(libs.material.color.utilities)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
