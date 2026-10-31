plugins {
    alias(libs.plugins.tianyan.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
