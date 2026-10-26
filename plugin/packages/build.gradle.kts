plugins {
    alias(libs.plugins.tianyan.jvm.library)
}

dependencies {
    api(project(":plugin:api"))
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
