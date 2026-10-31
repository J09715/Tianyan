plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.workflow"
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":runtime"))
    implementation(project(":harness"))
    implementation(project(":feature:theme"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
