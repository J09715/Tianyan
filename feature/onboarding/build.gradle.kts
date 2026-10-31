plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.onboarding"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    testImplementation(libs.junit)
}
