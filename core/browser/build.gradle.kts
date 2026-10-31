plugins {
    alias(libs.plugins.tianyan.jvm.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
