plugins {
    alias(libs.plugins.tianyan.android.library.compose)
}

android {
    namespace = "top.tianyan.app.feature.components"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":feature:theme"))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.coil)
    testImplementation(libs.junit)
}
