plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.workspace"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":runtime"))
    implementation(project(":project-template"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(project(":feature:theme"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
