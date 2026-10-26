plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.settings"
}

dependencies {
    implementation(project(":plugin:ui"))
    implementation(project(":feature:theme"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.coil)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
