plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.developer"
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":tools"))
    implementation(project(":runtime"))
    testImplementation(libs.junit)
}
