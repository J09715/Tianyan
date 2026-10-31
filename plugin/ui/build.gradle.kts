plugins {
    alias(libs.plugins.tianyan.android.library.compose)
}

android {
    namespace = "top.tianyan.app.plugin.ui"
}

dependencies {
    api(project(":plugin:api"))
    api(project(":plugin:runtime"))
    implementation(project(":feature:components"))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
